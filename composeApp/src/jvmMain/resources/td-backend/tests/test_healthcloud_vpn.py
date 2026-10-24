from __future__ import annotations

import unittest
from datetime import date
from urllib.error import HTTPError, URLError
from urllib.request import Request

from auto_td.healthcloud import (
    HealthCloudClient,
    VPN_URL,
    _OriginRedirectHandler,
    _RejectedRedirect,
)


LOGIN_PAGE = b'<form action="/login"><input name="username"><input name="password"></form>'
AUTHENTICATED_MAIN_PAGE = (
    b'<select id="xnxq"><option value="2026-2027-1" selected>2026-2027-1</option></select>'
)


class Response:
    def __init__(self, body: bytes, url: str, status: int = 200):
        self.body = body
        self.url = url
        self.status = status

    def read(self) -> bytes:
        return self.body

    def geturl(self) -> str:
        return self.url

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class ScriptedOpener:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests: list[Request] = []

    def open(self, request, timeout=15):
        self.requests.append(request)
        response = self.responses.pop(0)
        if isinstance(response, BaseException):
            raise response
        return response


def http_error(url: str, status: int = 503) -> HTTPError:
    return HTTPError(url, status, "unavailable", {}, None)


def _request_cookie(request: Request, name: str) -> str | None:
    header = request.get_header("Cookie") or request.unredirected_hdrs.get("Cookie", "")
    for item in header.split(";"):
        key, separator, value = item.strip().partition("=")
        if separator and key == name:
            return value
    return None


class HealthCloudVpnFallbackTest(unittest.TestCase):
    def test_query_reuses_term_from_the_authenticated_login_page(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(AUTHENTICATED_MAIN_PAGE, gateway_health + "main.php"),
            Response(b'{"status":1,"count":0,"infoProperty":1,"infoList":[]}', gateway_health + "api.php"),
        ])
        client = HealthCloudClient(opener=opener, webvpn=True,
                                   gateway_cookies=[{"name": "SESSION", "value": "fixture"}])
        self.assertEqual(0, client.query_today_valid_exit_count("student-fixture", None, date(2026, 10, 22)))
        self.assertEqual(2, len(opener.requests))
        self.assertTrue(opener.requests[-1].full_url.startswith(gateway_health + "api.php"))

    def test_webvpn_navigation_homepage_confirms_session_by_loading_score_page(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        homepage = b'<html><nav>Health cloud</nav></html>'
        score_page = AUTHENTICATED_MAIN_PAGE
        opener = ScriptedOpener([
            Response(homepage, gateway_health + "main.php"),
            Response(score_page, gateway_health + "main.php?module=stu&title=stu_sun_score"),
        ])
        client = HealthCloudClient(
            opener=opener,
            webvpn=True,
            gateway_cookies=[{"name": "SESSION", "value": "fixture"}],
        )

        client._login("student-fixture", "password-fixture")

        self.assertEqual(
            [gateway_health + "index.php", gateway_health + "main.php?module=stu&title=stu_sun_score"],
            [request.full_url for request in opener.requests],
        )
        self.assertTrue(all(request.data is None for request in opener.requests))

    def test_webvpn_navigation_homepage_rejects_score_page_without_valid_term(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(b"<nav>Health cloud</nav>", gateway_health + "main.php"),
            Response(b"<html>session expired</html>", gateway_health + "main.php?module=stu&title=stu_sun_score"),
        ])
        client = HealthCloudClient(opener=opener, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])

        with self.assertRaisesRegex(RuntimeError, "网关会话不可用"):
            client._login("student-fixture", "")

        self.assertEqual(2, len(opener.requests))
        self.assertTrue(all(request.data is None for request in opener.requests))

    def test_webvpn_navigation_homepage_rejects_score_page_login_form_without_post(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(b"<nav>Health cloud</nav>", gateway_health + "main.php"),
            Response(LOGIN_PAGE, gateway_health + "main.php?module=stu&title=stu_sun_score"),
        ])
        client = HealthCloudClient(opener=opener, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])

        with self.assertRaisesRegex(RuntimeError, "网关会话不可用"):
            client._login("student-fixture", "")

        self.assertEqual(2, len(opener.requests))
        self.assertTrue(all(request.data is None for request in opener.requests))

    def test_webvpn_navigation_homepage_rejects_external_score_page(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(b"<nav>Health cloud</nav>", gateway_health + "main.php"),
            Response(AUTHENTICATED_MAIN_PAGE, "https://attacker.example/main.php?module=stu"),
        ])
        client = HealthCloudClient(opener=opener, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])

        with self.assertRaisesRegex(RuntimeError, "响应地址不受支持"):
            client._login("student-fixture", "")

        self.assertEqual(2, len(opener.requests))
        self.assertTrue(all(request.data is None for request in opener.requests))

    def test_webvpn_login_form_with_empty_password_is_rejected_before_post(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([Response(LOGIN_PAGE, gateway_health + "index.php")])
        client = HealthCloudClient(opener=opener, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])

        with self.assertRaisesRegex(RuntimeError, "凭据或会话已过期"):
            client._login("student-fixture", "")

        self.assertEqual(1, len(opener.requests))
        self.assertIsNone(opener.requests[0].data)

    def test_webvpn_existing_gateway_session_accepts_valid_healthcloud_main_page_without_password_post(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(AUTHENTICATED_MAIN_PAGE, gateway_health + "main.php?module=stu"),
        ])
        client = HealthCloudClient(
            opener=opener,
            webvpn=True,
            gateway_cookies=[{"name": "SESSION", "value": "fixture"}],
        )

        client._login("student-fixture", "password-fixture")

        self.assertEqual([gateway_health + "index.php"], [request.full_url for request in opener.requests])
        self.assertTrue(all(request.data is None for request in opener.requests))

    def test_unknown_healthcloud_html_without_login_form_is_still_rejected(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(b"maintenance page", gateway_health + "index.php"),
        ])
        client = HealthCloudClient(
            opener=opener,
            webvpn=True,
            gateway_cookies=[{"name": "SESSION", "value": "fixture"}],
        )

        with self.assertRaisesRegex(RuntimeError, "网关会话不可用"):
            client._login("student-fixture", "password-fixture")

        self.assertEqual(1, len(opener.requests))
        self.assertIsNone(opener.requests[0].data)

    def test_valid_term_on_non_healthcloud_origin_cannot_bypass_login(self) -> None:
        gateway_sso = "https://d.buaa.edu.cn/https/77726476706e69737468656265737421e3e44ed225256951300d8db9d6562d/"
        opener = ScriptedOpener([
            Response(AUTHENTICATED_MAIN_PAGE, gateway_sso + "main.php"),
        ])
        client = HealthCloudClient(
            opener=opener,
            webvpn=True,
            gateway_cookies=[{"name": "SESSION", "value": "fixture"}],
        )

        with self.assertRaisesRegex(RuntimeError, "网关会话不可用"):
            client._login("student-fixture", "password-fixture")

        self.assertEqual(1, len(opener.requests))
        self.assertIsNone(opener.requests[0].data)

    def test_external_page_with_valid_term_is_rejected_before_password_post(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(AUTHENTICATED_MAIN_PAGE, "https://attacker.example/main.php"),
        ])
        client = HealthCloudClient(
            opener=opener,
            webvpn=True,
            gateway_cookies=[{"name": "SESSION", "value": "fixture"}],
        )

        with self.assertRaisesRegex(RuntimeError, "响应地址不受支持"):
            client._login("student-fixture", "password-fixture")

        self.assertEqual([gateway_health + "index.php"], [request.full_url for request in opener.requests])
        self.assertIsNone(opener.requests[0].data)

    def test_direct_and_explicit_vpn_existing_sessions_accept_valid_main_page(self) -> None:
        cases = (
            ("http://10.212.28.38/index.php", {}),
            (VPN_URL + "index.php", {"vpn_only": True}),
        )
        for index_url, options in cases:
            with self.subTest(index_url=index_url):
                main_url = index_url.rsplit("/", 1)[0] + "/main.php"
                opener = ScriptedOpener([Response(AUTHENTICATED_MAIN_PAGE, main_url)])
                client = HealthCloudClient(opener=opener, **options)

                client._login("student-fixture", "password-fixture")

                self.assertEqual([index_url], [request.full_url for request in opener.requests])
                self.assertIsNone(opener.requests[0].data)

    def test_webvpn_login_and_relative_form_action_stay_on_fixed_gateway_routes(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(b'<form action="login"><input name="username"><input name="password"><input name="execution" value="fixture"></form>', gateway_health + "index.php"),
            Response(b"logged in", gateway_health + "login"),
        ])
        client = HealthCloudClient(
            opener=opener,
            webvpn=True,
            gateway_cookies=[{"name": "SESSION", "value": "cookie-fixture"}],
        )

        client._login("student-fixture", "password-fixture")

        self.assertEqual([gateway_health + "index.php", gateway_health + "login"], [r.full_url for r in opener.requests])
        self.assertIn(b"execution=fixture", opener.requests[1].data)
        for request in opener.requests:
            self.assertEqual("cookie-fixture", _request_cookie(request, "SESSION"))
            self.assertEqual("d.buaa.edu.cn", request.host)

    def test_webvpn_sso_form_action_maps_to_the_fixed_official_sso_gateway_route(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        gateway_sso = "https://d.buaa.edu.cn/https/77726476706e69737468656265737421e3e44ed225256951300d8db9d6562d/"
        page = b'<form action="https://sso.buaa.edu.cn/cas/login"><input name="username"><input name="password"></form>'
        opener = ScriptedOpener([
            Response(page, gateway_health + "index.php"),
            Response(b"logged in", gateway_sso + "cas/login"),
        ])
        client = HealthCloudClient(opener=opener, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])

        client._login("student-fixture", "password-fixture")

        self.assertEqual(gateway_sso + "cas/login", opener.requests[1].full_url)
        self.assertEqual("fixture", _request_cookie(opener.requests[1], "SESSION"))

    def test_webvpn_root_relative_redirect_is_resolved_against_decoded_healthcloud_origin(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        client = HealthCloudClient(webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])
        handler = _OriginRedirectHandler(client)
        request = Request(gateway_health + "index.php")

        redirected = handler.redirect_request(request, None, 302, "redirect", {}, "https://d.buaa.edu.cn/login")

        self.assertEqual(gateway_health + "login", redirected.full_url)

    def test_webvpn_rejects_external_form_before_sending_password(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        page = b'<form action="https://attacker.example/collect"><input name="username"><input name="password"></form>'
        opener = ScriptedOpener([Response(page, gateway_health + "index.php")])
        client = HealthCloudClient(opener=opener, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])

        with self.assertRaisesRegex(RuntimeError, "跳转目标不受支持"):
            client._login("student-fixture", "password-fixture")

        self.assertEqual(1, len(opener.requests))
        self.assertIsNone(opener.requests[0].data)

    def test_webvpn_requires_gateway_cookie_before_any_credentials_are_requested(self) -> None:
        opener = ScriptedOpener([])
        client = HealthCloudClient(opener=opener, webvpn=True)

        with self.assertRaisesRegex(RuntimeError, "重新登录主账号后手动刷新"):
            client._login("student-fixture", "password-fixture")

        self.assertEqual([], opener.requests)

    def test_webvpn_failed_password_post_is_never_replayed_to_another_route(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([
            Response(LOGIN_PAGE, gateway_health + "index.php"),
            URLError("fixture post failed"),
        ])
        client = HealthCloudClient(opener=opener, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])

        with self.assertRaises(RuntimeError):
            client._login("student-fixture", "password-fixture")

        self.assertEqual(2, len(opener.requests))
        self.assertEqual(gateway_health + "index.php", opener.requests[0].full_url)
        self.assertEqual(gateway_health + "login", opener.requests[1].full_url)
        self.assertIn(b"password=password-fixture", opener.requests[1].data)

    def test_gateway_cookie_is_not_attached_to_an_unrelated_origin(self) -> None:
        gateway_health = "https://d.buaa.edu.cn/http/77726476706e69737468656265737421a1a70fce766226022646daf4/"
        opener = ScriptedOpener([Response(b"ok", gateway_health + "index.php")])
        client = HealthCloudClient(opener=opener, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "fixture"}])

        client._request(gateway_health + "index.php")
        with self.assertRaises(ValueError):
            client._request("https://attacker.example/collect")

        self.assertEqual(1, len(opener.requests))
        self.assertEqual("fixture", _request_cookie(opener.requests[0], "SESSION"))
        self.assertEqual("d.buaa.edu.cn", opener.requests[0].host)

    def test_vpn_only_route_never_attempts_direct_healthcloud(self) -> None:
        opener = ScriptedOpener([
            Response(LOGIN_PAGE, VPN_URL + "index.php"),
            Response(b"logged in", VPN_URL + "login"),
        ])
        client = HealthCloudClient(opener=opener, vpn_only=True)

        client._login("student-fixture", "password-fixture")

        self.assertEqual(
            [VPN_URL + "index.php", VPN_URL + "login"],
            [request.full_url for request in opener.requests],
        )

    def test_initial_direct_failure_falls_back_to_official_vpn_before_credentials(self) -> None:
        opener = ScriptedOpener([
            URLError("offline"),
            Response(LOGIN_PAGE, VPN_URL + "index.php"),
            Response(b"logged in", VPN_URL + "login"),
        ])
        client = HealthCloudClient(opener=opener, allow_vpn_fallback=True)

        client._login("student-fixture", "password-fixture")

        self.assertEqual(
            ["http://10.212.28.38/index.php", VPN_URL + "index.php", VPN_URL + "login"],
            [request.full_url for request in opener.requests],
        )
        self.assertIn(b"password-fixture", opener.requests[-1].data)

    def test_default_direct_client_never_falls_back_to_vpn(self) -> None:
        opener = ScriptedOpener([URLError("offline")])
        with self.assertRaises(RuntimeError):
            HealthCloudClient(opener=opener)._login("s", "p")
        self.assertEqual(["http://10.212.28.38/index.php"], [r.full_url for r in opener.requests])

    def test_explicit_vpn_base_is_blocked_without_campus_permission(self) -> None:
        opener = ScriptedOpener([Response(b"unused", VPN_URL + "index.php")])
        with self.assertRaises(ValueError):
            HealthCloudClient(VPN_URL, opener=opener)._request(VPN_URL + "index.php")
        self.assertEqual([], opener.requests)

    def test_vpn_flag_cannot_override_a_custom_base_url(self) -> None:
        opener = ScriptedOpener([URLError("offline")])
        with self.assertRaises(RuntimeError):
            HealthCloudClient(
                "http://fixture.invalid/", opener=opener, allow_vpn_fallback=True
            )._login("s", "p")
        self.assertEqual(["http://fixture.invalid/index.php"], [r.full_url for r in opener.requests])

    def test_direct_login_does_not_touch_vpn(self) -> None:
        opener = ScriptedOpener([
            Response(LOGIN_PAGE, "http://10.212.28.38/index.php"),
            Response(b"logged in", "http://10.212.28.38/login"),
        ])
        HealthCloudClient(opener=opener)._login("student-fixture", "password-fixture")
        self.assertEqual(2, len(opener.requests))
        self.assertTrue(all("vpn.buaa.edu.cn" not in request.full_url for request in opener.requests))

    def test_cas_form_action_and_hidden_fields_are_preserved(self) -> None:
        page = b'<form action="https://sso.buaa.edu.cn/cas/login"><input name="username"><input name="password"><input name="execution" value="fixture-ticket"></form>'
        opener = ScriptedOpener([
            Response(page, "http://10.212.28.38/index.php"),
            Response(b"logged in", "https://sso.buaa.edu.cn/cas/login"),
        ])
        HealthCloudClient(opener=opener)._login("s", "p")
        self.assertEqual("https://sso.buaa.edu.cn/cas/login", opener.requests[1].full_url)
        self.assertIn(b"execution=fixture-ticket", opener.requests[1].data)

    def test_reused_client_reprobes_direct_at_the_next_login(self) -> None:
        opener = ScriptedOpener([
            URLError("direct unavailable"),
            Response(LOGIN_PAGE, VPN_URL + "index.php"),
            Response(b"logged in", VPN_URL + "login"),
            Response(LOGIN_PAGE, "http://10.212.28.38/index.php"),
            Response(b"logged in", "http://10.212.28.38/login"),
        ])
        client = HealthCloudClient(opener=opener, allow_vpn_fallback=True)
        client._login("s", "p")
        self.assertEqual(VPN_URL + "index.php", client.base_url + "index.php")
        client._login("s", "p")
        self.assertEqual("http://10.212.28.38/index.php", opener.requests[3].full_url)
        self.assertEqual("http://10.212.28.38/", client.base_url)

    def test_custom_base_does_not_fallback_to_real_vpn(self) -> None:
        opener = ScriptedOpener([URLError("offline")])
        with self.assertRaises(RuntimeError):
            HealthCloudClient("http://fixture.invalid/", opener=opener)._login("s", "p")
        self.assertEqual(["http://fixture.invalid/index.php"], [r.full_url for r in opener.requests])

    def test_non_success_initial_direct_response_falls_back(self) -> None:
        opener = ScriptedOpener([
            http_error("http://10.212.28.38/index.php"),
            Response(LOGIN_PAGE, VPN_URL + "index.php"),
            Response(b"logged in", VPN_URL + "login"),
        ])
        HealthCloudClient(opener=opener, allow_vpn_fallback=True)._login("s", "p")
        self.assertEqual(VPN_URL + "index.php", opener.requests[1].full_url)

    def test_vpn_mode_rewrites_absolute_direct_healthcloud_url(self) -> None:
        opener = ScriptedOpener([
            URLError("direct unavailable"),
            Response(LOGIN_PAGE, VPN_URL + "index.php"),
            Response(b"logged in", VPN_URL + "login"),
            Response(b"ok", VPN_URL + "main.php?x=1"),
        ])
        client = HealthCloudClient(opener=opener, allow_vpn_fallback=True)
        client._login("s", "p")
        client._request("http://10.212.28.38/main.php?x=1")
        self.assertEqual(VPN_URL + "main.php?x=1", opener.requests[-1].full_url)

    def test_unrelated_login_form_is_rejected_before_password_is_sent(self) -> None:
        for action in (
            "https://attacker.example/collect",
            "https://student:secret@10-212-28-38.vpn.buaa.edu.cn:8118/login",
            "http://sso.buaa.edu.cn/cas",
        ):
            with self.subTest(action=action):
                page = f'<form action="{action}"><input name="username"><input name="password"></form>'.encode()
                opener = ScriptedOpener([Response(page, "http://10.212.28.38/index.php")])
                with self.assertRaisesRegex(RuntimeError, "VPN 网页会话未就绪"):
                    HealthCloudClient(opener=opener)._login("s", "p")
                self.assertEqual(1, len(opener.requests))
                self.assertIsNone(opener.requests[0].data)

    def test_cancellation_does_not_trigger_vpn_fallback(self) -> None:
        opener = ScriptedOpener([KeyboardInterrupt()])
        with self.assertRaises(KeyboardInterrupt):
            HealthCloudClient(opener=opener)._login("s", "p")
        self.assertEqual(["http://10.212.28.38/index.php"], [r.full_url for r in opener.requests])
        self.assertIsNone(opener.requests[0].data)

    def test_blocked_initial_redirect_does_not_trigger_vpn_fallback(self) -> None:
        opener = ScriptedOpener([_RejectedRedirect("blocked redirect")])
        with self.assertRaises(_RejectedRedirect):
            HealthCloudClient(opener=opener)._login("s", "p")
        self.assertEqual(1, len(opener.requests))

    def test_post_redirect_method_policy_preserves_only_get_conversions(self) -> None:
        client = HealthCloudClient()
        handler = _OriginRedirectHandler(client)
        post = Request("https://sso.buaa.edu.cn/cas", data=b"password=fixture")
        for status in (302, 303):
            redirected = handler.redirect_request(
                post, None, status, "redirect", {"Location": "/continue"},
                "https://sso.buaa.edu.cn/continue",
            )
            self.assertIsNotNone(redirected)
            self.assertEqual("GET", redirected.get_method())
            self.assertIsNone(redirected.data)
        for status in (307, 308):
            self.assertIsNone(handler.redirect_request(
                post, None, status, "redirect", {"Location": "/continue"},
                "https://sso.buaa.edu.cn/continue",
            ))

    def test_direct_sso_return_to_fixed_healthcloud_origin_is_followed(self) -> None:
        client = HealthCloudClient()
        handler = _OriginRedirectHandler(client)
        get = Request("https://sso.buaa.edu.cn/cas")
        redirected = handler.redirect_request(
            get, None, 302, "redirect", {}, "http://10.212.28.38/main.php?from=cas"
        )
        self.assertEqual("http://10.212.28.38/main.php?from=cas", redirected.full_url)
        self.assertEqual("GET", redirected.get_method())

    def test_vpn_sso_return_to_internal_ip_is_rewritten_to_https_vpn_origin(self) -> None:
        client = HealthCloudClient(allow_vpn_fallback=True)
        client.base_url = VPN_URL
        handler = _OriginRedirectHandler(client)
        get = Request("https://sso.buaa.edu.cn/cas")
        redirected = handler.redirect_request(
            get, None, 302, "redirect", {}, "http://10.212.28.38/main.php?from=cas"
        )
        self.assertEqual(VPN_URL + "main.php?from=cas", redirected.full_url)
        self.assertEqual("https", redirected.type)
        for target in (
            "https://attacker.example/collect",
            "http://10-212-28-38.vpn.buaa.edu.cn:8118/main.php",
            "http://attacker.example/collect",
        ):
            with self.subTest(target=target), self.assertRaises(_RejectedRedirect):
                handler.redirect_request(get, None, 302, "redirect", {}, target)

    def test_vpn_post_form_redirect_to_internal_ip_is_rewritten_before_following(self) -> None:
        client = HealthCloudClient(allow_vpn_fallback=True)
        client.base_url = VPN_URL
        handler = _OriginRedirectHandler(client)
        post = Request("https://sso.buaa.edu.cn/cas", data=b"fixture=1")
        redirected = handler.redirect_request(
            post, None, 302, "redirect", {}, "http://10.212.28.38/login"
        )
        self.assertEqual(VPN_URL + "login", redirected.full_url)
        self.assertEqual("GET", redirected.get_method())

    def test_failed_password_post_is_not_retried_on_vpn(self) -> None:
        opener = ScriptedOpener([
            Response(LOGIN_PAGE, "http://10.212.28.38/index.php"),
            URLError("post failed"),
        ])
        with self.assertRaises(RuntimeError):
            HealthCloudClient(opener=opener)._login("s", "p")
        self.assertEqual(2, len(opener.requests))
        self.assertIsNotNone(opener.requests[-1].data)

    def test_rejected_password_is_not_retried_on_vpn(self) -> None:
        opener = ScriptedOpener([
            Response(LOGIN_PAGE, "http://10.212.28.38/index.php"),
            Response(LOGIN_PAGE, "http://10.212.28.38/login"),
        ])
        with self.assertRaisesRegex(RuntimeError, "登录失败"):
            HealthCloudClient(opener=opener)._login("s", "p")
        self.assertEqual(2, len(opener.requests))
        self.assertIsNotNone(opener.requests[1].data)

    def test_vpn_portal_landing_is_reported_as_session_not_ready(self) -> None:
        opener = ScriptedOpener([
            URLError("direct unavailable"),
            Response(b"portal", "https://vpn.buaa.edu.cn/portal/#!/login"),
        ])
        with self.assertRaisesRegex(RuntimeError, "VPN 网页会话未就绪"):
            HealthCloudClient(opener=opener, allow_vpn_fallback=True)._login("s", "p")
        self.assertEqual(2, len(opener.requests))


if __name__ == "__main__":
    unittest.main()
