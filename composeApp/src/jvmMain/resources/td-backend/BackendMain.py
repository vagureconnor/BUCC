import argparse
import shutil
import sys
from pathlib import Path

from auto_td.storage import AppStorage
from auto_td.ui import serve_ui


def seed_default_images() -> None:
    bundle_root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    default_images = bundle_root / "default_images"
    if not default_images.is_dir():
        return

    storage = AppStorage()
    storage.initialize()
    for image in default_images.iterdir():
        if image.is_file():
            target = storage.images_dir / image.name
            if not target.exists():
                shutil.copy2(image, target)


def main() -> int:
    if sys.argv[1:3] == ["-m", "auto_td.cli"]:
        from auto_td.cli import main as cli_main

        return cli_main(sys.argv[3:])

    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    seed_default_images()
    return serve_ui(host="127.0.0.1", port=args.port, open_browser=False)


if __name__ == "__main__":
    raise SystemExit(main())
