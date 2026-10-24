from __future__ import annotations

import json
import math
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, timedelta
from http.cookiejar import CookieJar
from http.cookiejar import Cookie
from html.parser import HTMLParser
from typing import Any, Optional
from urllib.parse import urlencode, urljoin, urlsplit, urlunsplit
from urllib.request import ProxyHandler, HTTPCookieProcessor, HTTPRedirectHandler, Request, build_opener


BASE_URL = "http://10.212.28.38/"
VPN_URL = "https://10-212-28-38.vpn.buaa.edu.cn:8118/"
_SSO_ORIGIN = ("https", "sso.buaa.edu.cn", 443)
_WEBVPN_ORIGIN = ("https", "d.buaa.edu.cn", 443)
# These two public gateway route prefixes are the verified AES-CFB host encodings
# used by LocalWebVpnSupport (key and IV are both "wrdvpnisthebest!").
_WEBVPN_HEALTH_ROUTE = "/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
_WEBVPN_SSO_ROUTE = "/https/77726476706e69737468656265737421e3e44ed225256951300d8db9d6562d/"
_WEBVPN_URL = "https://d.buaa.edu.cn" + _WEBVPN_HEALTH_ROUTE
WEBVPN_SESSION_REQUIRED = "WebVPN 网关会话不可用，请重新登录主账号后手动刷新健康云次数"
RECORD_DETAIL_URL = (
    "api.php?module=stu&file=ajax_stu&title=stu_sun_score"
    "&action=getRecordDetail&isAjax=1"
)
PAGE_URL = "main.php?module=stu&title=stu_sun_score"
PAGE_SIZE = 20


class _FormParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.forms: list[dict[str, Any]] = []
        self._form: Optional[dict[str, Any]] = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        values = dict(attrs)
        if tag == "form":
            self._form = {"action": values.get("action", ""), "fields": {}}
        elif tag == "input" and self._form is not None and values.get("name"):
            self._form["fields"][values["name"]] = values.get("value", "") or ""

    def handle_endtag(self, tag: str) -> None:
        if tag == "form" and self._form is not None:
            self.forms.append(self._form)
            self._form = None


class _TermParser(HTMLParser):
    _VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}

    def __init__(self) -> None:
        super().__init__()
        self.term = ""
        self._target_depth = 0
        self._option_selected = False
        self._option_value = ""
        self._option_text: list[str] = []
        self._selected_term = ""
        self._text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        values = dict(attrs)
        if not self._target_depth and values.get("id") == "xnxq":
            if tag == "input":
                self.term = str(values.get("value") or "").strip()
                return
            self._target_depth = 1
            if tag == "option":
                self._start_option(values)
            return
        if self._target_depth:
            if tag == "option":
                self._start_option(values)
            if tag not in self._VOID_TAGS:
                self._target_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if self._option_selected and tag == "option":
            self._selected_term = self._option_value or "".join(self._option_text).strip()
            self._option_selected = False
        if self._target_depth:
            self._target_depth -= 1

    def handle_data(self, data: str) -> None:
        if self._target_depth:
            self._text.append(data)
        if self._option_selected:
            self._option_text.append(data)

    def _start_option(self, attrs: dict[str, Optional[str]]) -> None:
        if "selected" in attrs:
            self._option_selected = True
            self._option_value = str(attrs.get("value") or "").strip()
            self._option_text = []

    def result(self) -> str:
        return (self._selected_term or self.term or "".join(self._text)).strip()


class HealthCloudClient:
    def __init__(
        self,
        base_url: str = BASE_URL,
        opener=None,
        allow_vpn_fallback: bool = False,
        vpn_only: bool = False,
        webvpn: bool = False,
        gateway_cookies: Optional[list[dict[str, str]]] = None,
        proxy_url: Optional[str] = None,
    ):
        if vpn_only and webvpn:
            raise ValueError("健康云连接路由参数冲突")
        self._webvpn = bool(webvpn)
        self.base_url = _WEBVPN_URL if webvpn else VPN_URL if vpn_only else base_url.rstrip("/") + "/"
        self._vpn_allowed = bool((allow_vpn_fallback or vpn_only) and self.base_url in {BASE_URL, VPN_URL})
        self._allow_vpn_fallback = bool(allow_vpn_fallback and not vpn_only and self.base_url == BASE_URL)
        self._login_term: Optional[str] = None
        self._cookie_jar = CookieJar()
        self._seed_gateway_cookies(gateway_cookies or [])
        if proxy_url:
            proxy = urlsplit(proxy_url)
            if proxy.scheme != "http" or proxy.hostname != "127.0.0.1" or proxy.username or proxy.password or not proxy.port or proxy.path:
                raise ValueError("校园代理地址无效")
        handlers = ProxyHandler({"http": proxy_url, "https": proxy_url} if proxy_url else {})
        self.opener = opener or build_opener(handlers, HTTPCookieProcessor(self._cookie_jar), _OriginRedirectHandler(self))

    def _seed_gateway_cookies(self, cookies: list[dict[str, str]]) -> None:
        if not self._webvpn:
            return
        for item in cookies:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "")
            value = str(item.get("value") or "")
            if (
                not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name)
                or any(ord(char) < 0x20 or ord(char) == 0x7F for char in value)
                or ";" in value
                or len(name) > 256
                or len(value) > 4096
            ):
                continue
            self._cookie_jar.set_cookie(
                Cookie(
                    version=0,
                    name=name,
                    value=value,
                    port=None,
                    port_specified=False,
                    domain="d.buaa.edu.cn",
                    domain_specified=False,
                    domain_initial_dot=False,
                    path="/",
                    path_specified=True,
                    secure=True,
                    expires=None,
                    discard=True,
                    comment=None,
                    comment_url=None,
                    rest={"HttpOnly": None},
                    rfc2109=False,
                )
            )

    def query_today_valid_exit_count(
        self,
        student_id: str,
        password: str,
        day: date,
        event_no: str = "802",
    ) -> int:
        self._login(student_id, password)
        term = self._current_term()
        return self._query_day_count(student_id, day.isoformat(), term, event_no)

    def query_semester_valid_exit_count(
        self,
        student_id: str,
        password: str,
        through_day: date,
        event_no: str = "802",
        cached_term: Optional[str] = None,
        cached_day_counts: Optional[dict[str, int]] = None,
    ) -> dict[str, Any]:
        self._login(student_id, password)
        term = self._current_term()
        return self._query_semester_valid_exit_count(
            student_id,
            through_day,
            term,
            event_no,
            cached_term,
            cached_day_counts,
        )

    def query_counts(
        self,
        student_id: str,
        password: Optional[str],
        through_day: date,
        event_no: str = "802",
        cached_term: Optional[str] = None,
        cached_day_counts: Optional[dict[str, int]] = None,
        refresh_total: bool = True,
    ) -> dict[str, Any]:
        self._login(student_id, password)
        term = self._current_term()
        day_key = through_day.isoformat()
        today_count = self._query_day_count(student_id, day_key, term, event_no)
        refresh_total = refresh_total or cached_term != term
        total = None
        total_error = None
        if refresh_total:
            try:
                total = self._query_semester_valid_exit_count(
                    student_id,
                    through_day,
                    term,
                    event_no,
                    cached_term,
                    cached_day_counts,
                    refreshed_day_counts={day_key: today_count},
                )
            except Exception as exc:
                total_error = str(exc)
        return {"term": term, "today_count": today_count, "total": total, "total_error": total_error}

    def _query_semester_valid_exit_count(
        self,
        student_id: str,
        through_day: date,
        term: str,
        event_no: str,
        cached_term: Optional[str],
        cached_day_counts: Optional[dict[str, int]],
        refreshed_day_counts: Optional[dict[str, int]] = None,
    ) -> dict[str, Any]:
        start_day = _term_start_day(term)
        cached = {
            str(key): min(3, max(0, int(value)))
            for key, value in (cached_day_counts or {}).items()
            if cached_term == term
            and start_day <= date.fromisoformat(str(key)) <= through_day
        }
        day_counts: dict[str, int] = {}
        refresh_from = max(start_day, through_day - timedelta(days=1))
        refresh_days: list[str] = []
        for key, value in (refreshed_day_counts or {}).items():
            if start_day <= date.fromisoformat(key) <= through_day:
                day_counts[key] = min(3, max(0, int(value)))
        current = start_day
        while current <= through_day:
            key = current.isoformat()
            if key not in day_counts:
                if key in cached and current < refresh_from:
                    day_counts[key] = cached[key]
                else:
                    refresh_days.append(key)
            current += timedelta(days=1)
        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = {
                executor.submit(self._fetch_records, student_id, key, term, event_no): key
                for key in refresh_days
            }
            for future in as_completed(futures):
                key = futures[future]
                records, info_property = future.result()
                day_counts[key] = min(3, count_valid_exit_records(records, key, info_property=info_property))
        return {"term": term, "count": sum(day_counts.values()), "days": day_counts}

    def _query_day_count(self, student_id: str, day: str, term: str, event_no: str) -> int:
        records, info_property = self._fetch_records(student_id, day, term, event_no)
        return count_valid_exit_records(records, day, info_property=info_property)

    def _current_term(self) -> str:
        if self._login_term is not None:
            term, self._login_term = self._login_term, None
            return term
        page = self._request(urljoin(self.base_url, PAGE_URL)).decode("utf-8", errors="replace")
        term = _extract_term(page)
        if not term:
            raise RuntimeError("健康云未返回学年学期信息，请检查登录状态")
        return term

    def _login(self, student_id: str, password: Optional[str]) -> None:
        self._login_term = None
        if self._webvpn and not any(True for _cookie in self._cookie_jar):
            raise RuntimeError(WEBVPN_SESSION_REQUIRED)
        if self._allow_vpn_fallback:
            self.base_url = BASE_URL
        login_url = urljoin(self.base_url, "index.php")
        try:
            page_bytes, page_url = self._request_with_url(login_url)
        except (_VpnSessionNotReady, _RejectedRedirect):
            raise
        except _HealthCloudRequestFailure:
            if not self._allow_vpn_fallback or self.base_url != BASE_URL:
                raise
            self.base_url = VPN_URL
            page_bytes, page_url = self._request_with_url(urljoin(self.base_url, "index.php"))
        if _host(page_url) == "vpn.buaa.edu.cn":
            raise RuntimeError("健康云 VPN 网页会话未就绪，请确认 VPN 连接与网页访问权限后重试")
        page = page_bytes.decode("utf-8", errors="replace")
        form = self._login_form(page)
        if form is None:
            if self._is_authenticated_healthcloud_page(page, page_url):
                return
            if self._is_trusted_healthcloud_main_page(page_url):
                score_page_bytes, score_page_url = self._request_with_url(
                    urljoin(self.base_url, PAGE_URL)
                )
                score_page = score_page_bytes.decode("utf-8", errors="replace")
                if self._is_authenticated_healthcloud_page(score_page, score_page_url):
                    return
            if self._webvpn:
                raise RuntimeError(WEBVPN_SESSION_REQUIRED)
            if self.base_url == VPN_URL:
                raise RuntimeError("健康云 VPN 网页会话未就绪，请确认 VPN 连接与网页访问权限后重试")
            raise RuntimeError("健康云登录页面格式无法识别")
        if not password or not password.strip():
            raise RuntimeError("健康云凭据或会话已过期，请在设置中保存健康云账号密码后重试")
        fields = dict(form["fields"])
        fields.update({"username": student_id, "password": password})
        fields.setdefault("type", "username_password")
        fields.setdefault("_eventId", "submit")
        fields.setdefault("submit", "LOGIN")
        try:
            form_base = _webvpn_upstream_url(page_url) if self._webvpn else page_url
            if not form_base:
                raise RuntimeError(WEBVPN_SESSION_REQUIRED)
            action = self._approved_url(
                urljoin(form_base, form["action"] or "login"), source_url=page_url
            )
        except ValueError as exc:
            if self._webvpn:
                raise RuntimeError("健康云登录页面跳转目标不受支持") from exc
            raise RuntimeError("健康云 VPN 网页会话未就绪，请确认 VPN 连接与网页访问权限后重试") from exc
        response = self._request(action, urlencode(fields).encode("utf-8"))
        result = response.decode("utf-8", errors="replace")
        if self._login_form(result) is not None:
            raise RuntimeError("健康云登录失败，请检查学号和密码")

    @staticmethod
    def _login_form(page: str) -> Optional[dict[str, Any]]:
        parser = _FormParser()
        parser.feed(page)
        for form in parser.forms:
            fields = form["fields"]
            if "username" in fields and "password" in fields:
                return form
        return None

    def _is_authenticated_healthcloud_page(self, page: str, page_url: str) -> bool:
        if not self._is_trusted_healthcloud_main_page(page_url):
            return False
        try:
            term = _extract_term(page)
            _term_start_day(term)
        except (RuntimeError, ValueError):
            return False
        self._login_term = term
        return True

    def _is_trusted_healthcloud_main_page(self, page_url: str) -> bool:
        upstream_url = None
        if self._webvpn:
            upstream_url = _webvpn_upstream_url(page_url)
            if upstream_url is None or _origin(urlsplit(upstream_url)) != _origin(urlsplit(BASE_URL)):
                return False
        elif self.base_url in {BASE_URL, VPN_URL}:
            if _origin(urlsplit(page_url)) != _origin(urlsplit(self.base_url)):
                return False
            upstream_url = page_url
        if upstream_url is None:
            return False
        return urlsplit(upstream_url).path.rstrip("/") == "/main.php"

    def _fetch_records(
        self,
        student_id: str,
        day: str,
        term: str,
        event_no: str,
    ) -> tuple[list[dict[str, Any]], int]:
        endpoint = urljoin(self.base_url, RECORD_DETAIL_URL)
        rows: list[dict[str, Any]] = []
        page = 1
        page_count = 1
        info_property = 1
        while page <= page_count:
            fields = {
                "stuNo": student_id,
                "eventNo": str(event_no),
                "detailDate": day,
                "detailYearTerm": term,
                "trgtPage": str(page),
                "pageSize": str(PAGE_SIZE),
            }
            response = self._request(endpoint, urlencode(fields).encode("utf-8"))
            try:
                result = json.loads(response.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise RuntimeError("健康云流水响应格式无效") from exc
            if str(result.get("status")) != "1":
                raise RuntimeError(str(result.get("msg") or "健康云流水查询失败"))
            count = int(result.get("count") or 0)
            info_property = int(result.get("infoProperty", info_property))
            page_count = max(1, math.ceil(count / PAGE_SIZE))
            info_list = result.get("infoList") or []
            if isinstance(info_list, list):
                rows.extend(row for row in info_list if isinstance(row, dict))
            page += 1
        return rows, info_property

    def _request(self, url: str, data: Optional[bytes] = None) -> bytes:
        return self._request_with_url(url, data)[0]

    def _approved_url(self, url: str, source_url: Optional[str] = None) -> str:
        parts = urlsplit(url)
        if parts.username is not None or parts.password is not None:
            raise ValueError("URL credentials are not allowed")
        if self._webvpn:
            if _webvpn_upstream_url(url) is not None:
                return url
            mapped = _webvpn_gateway_url(url)
            if mapped is not None:
                return mapped
            if _origin(parts) == _WEBVPN_ORIGIN and source_url:
                source_upstream = _webvpn_upstream_url(source_url)
                if source_upstream is not None:
                    relative_target = urlunsplit(("", "", parts.path, parts.query, parts.fragment))
                    mapped = _webvpn_gateway_url(urljoin(source_upstream, relative_target))
                    if mapped is not None:
                        return mapped
            raise ValueError("URL origin is not allowed")
        if self.base_url == VPN_URL and parts.scheme == "http" and parts.hostname == "10.212.28.38" and (parts.port or 80) == 80:
            url = urljoin(VPN_URL, parts.path.lstrip("/"))
            if parts.query:
                url += "?" + parts.query
            parts = urlsplit(url)
        origin = _origin(parts)
        selected_origin = _origin(urlsplit(self.base_url))
        if origin == _origin(urlsplit(VPN_URL)) and not self._vpn_allowed:
            raise ValueError("官方健康云 VPN 地址未获授权")
        if origin not in {selected_origin, _SSO_ORIGIN}:
            raise ValueError("URL origin is not allowed")
        if (
            source_url
            and urlsplit(source_url).scheme.lower() == "https"
            and parts.scheme.lower() == "http"
            and not (self.base_url == BASE_URL and origin == selected_origin)
        ):
            raise ValueError("HTTPS downgrade is not allowed")
        return url

    def _request_with_url(self, url: str, data: Optional[bytes] = None) -> tuple[bytes, str]:
        url = self._approved_url(url)
        headers = {"User-Agent": "AutoTD-Desktop"}
        if data is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        request = Request(url, data=data, headers=headers)
        if self._webvpn and _origin(urlsplit(url)) == _WEBVPN_ORIGIN:
            self._cookie_jar.add_cookie_header(request)
        try:
            response = self.opener.open(request, timeout=15)
        except (_VpnSessionNotReady, _RejectedRedirect):
            raise
        except Exception as exc:
            raise _HealthCloudRequestFailure("连接健康云失败") from exc
        try:
            with response:
                final_url = response.geturl()
                if _host(final_url) == "vpn.buaa.edu.cn":
                    if self._webvpn:
                        raise _VpnSessionNotReady(WEBVPN_SESSION_REQUIRED)
                    raise _VpnSessionNotReady(
                        "健康云 VPN 网页会话未就绪，请确认 VPN 连接与网页访问权限后重试"
                    )
                final_url = self._approved_url(final_url)
                status = getattr(response, "status", 200)
                if status < 200 or status >= 300:
                    raise _HealthCloudRequestFailure("健康云返回错误状态")
                return response.read(), final_url
        except (_VpnSessionNotReady, _RejectedRedirect, _HealthCloudRequestFailure):
            raise
        except ValueError as exc:
            raise _RejectedRedirect("健康云响应地址不受支持") from exc
        except Exception as exc:
            raise _HealthCloudRequestFailure("连接健康云失败") from exc


def _origin(parts) -> tuple[str, str, int]:
    scheme = parts.scheme.lower()
    host = (parts.hostname or "").lower()
    try:
        port = parts.port or (443 if scheme == "https" else 80 if scheme == "http" else -1)
    except ValueError:
        return (scheme, host, -1)
    return (scheme, host, port)


def _host(url: str) -> str:
    return (urlsplit(url).hostname or "").lower()


def _webvpn_gateway_url(upstream_url: str) -> Optional[str]:
    parts = urlsplit(upstream_url)
    if parts.username is not None or parts.password is not None:
        return None
    origin = _origin(parts)
    if origin == ("http", "10.212.28.38", 80):
        route = _WEBVPN_HEALTH_ROUTE
    elif origin == _SSO_ORIGIN:
        route = _WEBVPN_SSO_ROUTE
    else:
        return None
    path = parts.path.lstrip("/")
    return (
        "https://d.buaa.edu.cn" + route + path
        + ("?" + parts.query if parts.query else "")
        + ("#" + parts.fragment if parts.fragment else "")
    )


def _webvpn_upstream_url(gateway_url: str) -> Optional[str]:
    parts = urlsplit(gateway_url)
    if _origin(parts) != _WEBVPN_ORIGIN:
        return None
    for route, origin in (
        (_WEBVPN_HEALTH_ROUTE, "http://10.212.28.38/"),
        (_WEBVPN_SSO_ROUTE, "https://sso.buaa.edu.cn/"),
    ):
        if parts.path.startswith(route):
            suffix = parts.path[len(route):]
            path = suffix if not suffix or suffix.startswith("/") else "/" + suffix
            return (
                origin.rstrip("/") + path
                + ("?" + parts.query if parts.query else "")
                + ("#" + parts.fragment if parts.fragment else "")
            )
    return None


class _OriginRedirectHandler(HTTPRedirectHandler):
    def __init__(self, client: HealthCloudClient):
        super().__init__()
        self.client = client

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if self.client._webvpn and _host(newurl) == "vpn.buaa.edu.cn":
            raise _VpnSessionNotReady(WEBVPN_SESSION_REQUIRED)
        try:
            newurl = self.client._approved_url(newurl, source_url=req.full_url)
        except ValueError as exc:
            if _host(newurl) == "vpn.buaa.edu.cn":
                raise _VpnSessionNotReady(
                    "健康云 VPN 网页会话未就绪，请确认 VPN 连接与网页访问权限后重试"
                ) from exc
            raise _RejectedRedirect("健康云重定向目标不受支持") from exc
        if req.get_method() == "POST" and code in (307, 308):
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class _VpnSessionNotReady(RuntimeError):
    pass


class _RejectedRedirect(RuntimeError):
    pass


class _HealthCloudRequestFailure(RuntimeError):
    pass


def count_valid_exit_records(records: list[dict[str, Any]], day: str, info_property: int = 1) -> int:
    return sum(
        1
        for record in records
        if _is_valid_record(record, info_property)
        and "出口" in str(record.get("location", ""))
        and str(record.get("swaptime", ""))[:10] == day
    )


def _extract_term(page: str) -> str:
    parser = _TermParser()
    parser.feed(page)
    term = parser.result()
    if term:
        return term

    function = re.search(
        r"function\s+getStuEventDetail\s*\([^)]*\)(.*?)(?=function\s+getRecordDetail)",
        page,
        re.DOTALL,
    )
    if function is None:
        return ""
    year = re.search(r"\bvar\s+xn\s*=\s*[\"']([^\"']+)[\"']", function.group(1))
    semester = re.search(r"\bvar\s+xq\s*=\s*[\"']([^\"']+)[\"']", function.group(1))
    if year is None or semester is None:
        return ""
    return f"{year.group(1)}-{semester.group(1)}"


def _term_start_day(term: str) -> date:
    match = re.fullmatch(r"(\d{4})-(\d{4})-([1-3])", term)
    if match is None:
        raise RuntimeError("健康云学期格式无法识别")
    first_year, second_year, semester = (int(value) for value in match.groups())
    if semester == 1:
        return date(first_year, 8, 1)
    if semester == 2:
        return date(second_year, 1, 1)
    return date(second_year, 5, 1)


def _is_valid_record(record: dict[str, Any], info_property: int) -> bool:
    mark = str(record.get("mark", "0"))
    if mark == "1":
        return True
    return info_property != 1 and mark == "0" and str(record.get("notmarkreason") or "") == ""
