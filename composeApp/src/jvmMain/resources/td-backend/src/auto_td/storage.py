import json
import shutil
from datetime import datetime
from importlib import resources
from pathlib import Path
from typing import Any, Dict, List, Optional

from .constants import DEFAULT_POLL_SECONDS, DEFAULT_WINDOWS
from .models import User
from .paths import get_app_home


class AppStorage:
    def __init__(self, home: Optional[Path] = None):
        self.home = (home or get_app_home()).expanduser().resolve()
        self.images_dir = self.home / "images"
        self.logs_dir = self.home / "logs"
        self.config_path = self.home / "config.json"
        self.users_path = self.home / "users.json"
        self.settings_path = self.home / "settings.json"
        self.state_path = self.home / "state.json"
        self.pid_path = self.home / "autotd.pid"

    def initialize(self, source_dir: Optional[Path] = None) -> None:
        self.images_dir.mkdir(parents=True, exist_ok=True)
        self.logs_dir.mkdir(parents=True, exist_ok=True)

        if source_dir is not None:
            self._import_legacy_project(Path(source_dir))
        else:
            if not self.config_path.exists():
                self._write_template_config()
            else:
                self._migrate_localhost_server_default()
            if not self.users_path.exists():
                self.save_users({})

        if not self.settings_path.exists():
            self.save_settings(default_settings())
        if not self.state_path.exists():
            self.save_state(default_state())


    def load_config(self) -> Dict[str, Any]:
        return _read_json(self.config_path)

    def save_config(self, config: Dict[str, Any]) -> None:
        _write_json(self.config_path, config)

    def load_users(self) -> Dict[str, Dict[str, Any]]:
        payload = _read_json(self.users_path) if self.users_path.exists() else {"users": {}}
        return dict(payload.get("users", {}))

    def save_users(self, users: Dict[str, Dict[str, Any]]) -> None:
        _write_json(self.users_path, {"users": users})

    def list_users(self) -> List[User]:
        return [User.from_dict(data) for data in self.load_users().values()]

    def get_user(self, student_id: str) -> Optional[Dict[str, Any]]:
        return self.load_users().get(str(student_id))

    def get_user_model(self, student_id: str) -> User:
        data = self.get_user(student_id)
        if data is None:
            raise KeyError(f"用户不存在: {student_id}")
        return User.from_dict(data)

    def save_user(self, user: User) -> None:
        self.validate_user(user)
        users = self.load_users()
        users[user.student_id] = user.to_dict()
        self.save_users(users)

    def delete_user(self, student_id: str) -> bool:
        users = self.load_users()
        existed = users.pop(str(student_id), None) is not None
        self.save_users(users)
        return existed

    def validate_user(self, user: User) -> None:
        machine_ids = {int(machine["id"]) for machine in self.load_config().get("machine", [])}
        if user.entrance_machine_id not in machine_ids:
            raise ValueError(f"入口机器不存在: {user.entrance_machine_id}")
        if user.exit_machine_id not in machine_ids:
            raise ValueError(f"出口机器不存在: {user.exit_machine_id}")
        for image in (user.entrance_image, user.exit_image):
            if not (self.images_dir / image).exists():
                raise FileNotFoundError(f"图片不存在: {image}")

    def add_image(self, source: Path, name: Optional[str] = None, overwrite: bool = False) -> str:
        source = Path(source).expanduser().resolve()
        if not source.exists():
            raise FileNotFoundError(f"图片文件不存在: {source}")
        image_name = name or source.name
        if Path(image_name).name != image_name:
            raise ValueError("图片名称不能包含目录")
        target = self.images_dir / image_name
        if target.exists() and not overwrite:
            raise FileExistsError(f"图片已存在: {image_name}")
        self.images_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        return image_name

    def list_images(self) -> List[str]:
        if not self.images_dir.exists():
            return []
        return sorted(path.name for path in self.images_dir.iterdir() if path.is_file())

    def delete_image(self, name: str) -> bool:
        image_name = Path(str(name)).name
        if image_name != str(name):
            raise ValueError("图片名称不能包含目录")
        path = self.images_dir / image_name
        try:
            path.unlink()
        except FileNotFoundError:
            return False
        return True

    def image_path(self, name: str) -> Path:
        return self.images_dir / name

    def load_settings(self) -> Dict[str, Any]:
        settings = _read_json(self.settings_path) if self.settings_path.exists() else default_settings()
        settings.setdefault("schedule", {}).pop("enabled", None)
        return settings

    def save_settings(self, settings: Dict[str, Any]) -> None:
        _write_json(self.settings_path, settings)

    def update_settings(self, patch: Dict[str, Any]) -> None:
        settings = self.load_settings()
        _deep_update(settings, patch)
        self.save_settings(settings)

    def load_state(self) -> Dict[str, Any]:
        return _read_json(self.state_path) if self.state_path.exists() else default_state()

    def save_state(self, state: Dict[str, Any]) -> None:
        _write_json(self.state_path, state)

    def set_last_count(
        self,
        student_id: str,
        count: int,
        when: Optional[datetime] = None,
    ) -> None:
        state = self.load_state()
        counts = state.setdefault("counts", {})
        counts[str(student_id)] = {
            "count": int(count),
            "updated_at": (when or datetime.now()).isoformat(timespec="seconds"),
        }
        self.save_state(state)

    def get_last_count(self, student_id: str) -> Optional[int]:
        count_info = self.load_state().get("counts", {}).get(str(student_id))
        if not count_info:
            return None
        return int(count_info["count"])

    def set_daily_td_count(
        self,
        student_id: str,
        day: str,
        count: int,
        when: Optional[datetime] = None,
    ) -> None:
        state = self.load_state()
        counts = state.setdefault("daily_td_counts", {})
        counts[str(student_id)] = {
            "day": str(day),
            "count": int(count),
            "updated_at": (when or datetime.now()).isoformat(timespec="seconds"),
        }
        self.save_state(state)

    def get_daily_td_count(self, student_id: str, day: str) -> Optional[int]:
        value = self.load_state().get("daily_td_counts", {}).get(str(student_id)) or {}
        if value.get("day") != str(day):
            return None
        return int(value["count"])

    def get_daily_td_count_info(self, student_id: str, day: str) -> Optional[dict[str, Any]]:
        value = self.load_state().get("daily_td_counts", {}).get(str(student_id)) or {}
        if value.get("day") != str(day):
            return None
        return dict(value)

    def get_healthcloud_total_info(self, student_id: str) -> Optional[dict[str, Any]]:
        value = self.load_state().get("healthcloud_totals", {}).get(str(student_id))
        return dict(value) if isinstance(value, dict) else None

    def set_healthcloud_total(
        self,
        student_id: str,
        term: str,
        count: int,
        days: Dict[str, int],
        when: Optional[datetime] = None,
    ) -> None:
        state = self.load_state()
        totals = state.setdefault("healthcloud_totals", {})
        totals[str(student_id)] = {
            "term": str(term),
            "count": int(count),
            "days": {str(day): int(day_count) for day, day_count in days.items()},
            "updated_at": (when or datetime.now()).isoformat(timespec="seconds"),
        }
        self.save_state(state)

    def set_last_exit_request(
        self,
        student_id: str,
        when: datetime,
        cooldown_seconds: int,
    ) -> None:
        state = self.load_state()
        exits = state.setdefault("exit_requests", {})
        exits[str(student_id)] = {
            "sent_at": when.isoformat(timespec="seconds"),
            "cooldown_seconds": int(cooldown_seconds),
        }
        self.save_state(state)

    def get_last_exit_request(self, student_id: str) -> Optional[dict[str, Any]]:
        value = self.load_state().get("exit_requests", {}).get(str(student_id))
        return dict(value) if value else None

    def _import_legacy_project(self, source_dir: Path) -> None:
        source_dir = source_dir.expanduser().resolve()
        config_source = source_dir / "config.json"
        if config_source.exists():
            shutil.copy2(config_source, self.config_path)
        elif not self.config_path.exists():
            self._write_template_config()

        source_images = source_dir / "images"
        if source_images.exists():
            for image in source_images.iterdir():
                if image.is_file():
                    shutil.copy2(image, self.images_dir / image.name)

        users: Dict[str, Dict[str, Any]] = {}
        jsonl_path = source_dir / "user_config.jsonl"
        if jsonl_path.exists():
            for raw in load_legacy_jsonl(jsonl_path):
                for key in ("entrance_photo_path", "exit_photo_path"):
                    value = raw.get(key)
                    if value:
                        path = Path(str(value))
                        full_path = path if path.is_absolute() else source_dir / path
                        if full_path.exists():
                            shutil.copy2(full_path, self.images_dir / full_path.name)
                user = User.from_dict(raw)
                users[user.student_id] = user.to_dict()
        self.save_users(users)

    def _write_template_config(self) -> None:
        template = resources.files("auto_td").joinpath("templates/config.json")
        self.config_path.write_text(template.read_text(encoding="utf-8"), encoding="utf-8")

    def _migrate_localhost_server_default(self) -> None:
        config = self.load_config()
        server = config.get("server", {})
        if server.get("ip") != "127.0.0.1" or int(server.get("port", 0)) != 8888:
            return
        template = json.loads(resources.files("auto_td").joinpath("templates/config.json").read_text(encoding="utf-8"))
        server["ip"] = template["server"]["ip"]
        server["port"] = template["server"]["port"]
        if "timeout" in template["server"] and "timeout" not in server:
            server["timeout"] = template["server"]["timeout"]
        config["server"] = server
        self.save_config(config)


def default_settings() -> Dict[str, Any]:
    return {
        "schedule": {
            "poll_seconds": DEFAULT_POLL_SECONDS,
            "windows": list(DEFAULT_WINDOWS),
        }
    }


def default_state() -> Dict[str, Any]:
    return {
        "schedule": {"last_attempt_date": None},
        "counts": {},
        "daily_td_counts": {},
        "healthcloud_totals": {},
        "exit_requests": {},
    }


def load_legacy_jsonl(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped:
            continue
        try:
            value = json.loads(stripped)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSON in {path} line {line_no}: {exc.msg}") from exc
        if not isinstance(value, dict):
            raise ValueError(f"Invalid user config in {path} line {line_no}: expected object")
        rows.append(value)
    return rows


def _read_json(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def _write_json(path: Path, data: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def _deep_update(target: Dict[str, Any], patch: Dict[str, Any]) -> None:
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            _deep_update(target[key], value)
        else:
            target[key] = value
