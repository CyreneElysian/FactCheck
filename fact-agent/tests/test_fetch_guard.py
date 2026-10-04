"""
抓取网页的 SSRF 防护测试（app/services/detect_service.py 的 _assert_url_allowed / _fetch_page_text）

背景：被抓取的 URL 来自搜索引擎结果，属于不可信输入。若不加限制，只要某条结果指向
http://169.254.169.254/（云元数据）、http://127.0.0.1:8080/（内网服务）等地址，
就能借服务端发起内网请求。

这些用例完全离线：不需要 DNS（用字面量 IP 或打桩 getaddrinfo），也不真正发请求。
"""
import socket
import unittest
from unittest.mock import patch

import requests

from app.services.detect_service import DetectService, _assert_url_allowed, _host_is_blocked

# 一个公网字面量 IP：用它避免测试依赖 DNS
PUBLIC_IP = "93.184.216.34"


class BlockedUrlTest(unittest.TestCase):
    """必须被拒绝的 URL"""

    def assertBlocked(self, url, expect_in_message=None):
        with self.assertRaises(ValueError, msg=f"应当拒绝：{url}") as ctx:
            _assert_url_allowed(url)
        if expect_in_message:
            self.assertIn(expect_in_message, str(ctx.exception))

    def test_loopback_and_localhost(self):
        self.assertBlocked("http://127.0.0.1:8080/api/admin/records")
        self.assertBlocked("http://127.1.2.3/")
        self.assertBlocked("http://localhost/")
        self.assertBlocked("http://localhost:8001/health")
        self.assertBlocked("http://foo.localhost/", "foo.localhost")
        self.assertBlocked("http://[::1]/", "::1")

    def test_private_and_link_local_ranges(self):
        self.assertBlocked("http://10.0.0.5/")
        self.assertBlocked("http://192.168.1.1/")
        self.assertBlocked("http://172.16.0.1/")
        self.assertBlocked("http://169.254.169.254/latest/meta-data/", "169.254.169.254")  # 云元数据
        self.assertBlocked("http://0.0.0.0/")
        # 下面两类"既不是私网、也不可路由"，靠 is_private 判不出来，必须靠 not is_global
        self.assertBlocked("http://100.64.0.1/", "100.64.0.1")   # CGNAT / RFC 6598
        self.assertBlocked("http://192.0.2.1/")                  # TEST-NET-1 / RFC 5737

    def test_internal_name_suffixes(self):
        self.assertBlocked("http://db.internal/")
        self.assertBlocked("http://printer.local/")
        self.assertBlocked("http://metadata/", "metadata")

    def test_non_http_schemes(self):
        self.assertBlocked("file:///etc/passwd", "协议")
        self.assertBlocked("ftp://example.com/x", "协议")
        self.assertBlocked("gopher://example.com/", "协议")
        self.assertBlocked("http:///no-host", "主机名")

    def test_dns_failure_is_fail_closed(self):
        """域名解析失败时按不可访问处理，而不是放行"""
        with patch("socket.getaddrinfo", side_effect=OSError("dns down")):
            self.assertTrue(_host_is_blocked("nonexistent.example.invalid"))

    def test_domain_resolving_to_private_ip_is_blocked(self):
        """域名解析到内网地址（DNS 指向内网 / 简易 DNS rebinding）也必须拦住"""
        fake = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 0))]
        with patch("socket.getaddrinfo", return_value=fake):
            self.assertTrue(_host_is_blocked("evil.example.com"))
            with self.assertRaises(ValueError):
                _assert_url_allowed("http://evil.example.com/")

    def test_domain_resolving_to_public_ip_is_allowed(self):
        fake = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (PUBLIC_IP, 0))]
        with patch("socket.getaddrinfo", return_value=fake):
            self.assertFalse(_host_is_blocked("news.example.com"))
            _assert_url_allowed("https://news.example.com/article")  # 不应抛异常


class AllowedUrlTest(unittest.TestCase):
    """允许抓取的 URL"""

    def test_public_ip_literals_allowed(self):
        _assert_url_allowed(f"http://{PUBLIC_IP}/a")
        _assert_url_allowed(f"https://{PUBLIC_IP}/a?b=1")

    def test_public_ipv6_allowed(self):
        _assert_url_allowed("https://[2606:4700:4700::1111]/")


class FakeResponse:
    """极简的假响应，避免依赖 requests 的真实网络栈

    说明：`_fetch_page_text` 现在按**响应字节**（`content`）自行探测编码解码，
    不再用 `resp.text`（后者在响应头无 charset 时会被 requests 兜底成 ISO-8859-1，
    导致 GBK 页面中文乱码，见 tests/test_page_encoding.py）。
    因此这个假响应必须像真实 `requests.Response` 一样同时提供 `content` 与 `encoding`。
    """

    def __init__(self, status_code=200, headers=None,
                 text="<html><body>正文内容</body></html>",
                 content: bytes = None, encoding="utf-8"):
        self.status_code = status_code
        self.headers = headers or {}
        self.text = text
        # 可显式给字节；不传则用 text 的 UTF-8 编码，保持与旧用例等价
        self.content = content if content is not None else text.encode("utf-8")
        self.encoding = encoding

    @property
    def apparent_encoding(self):
        # 复刻 requests 在缺少 chardet 时的真实行为（本项目 venv 就没装 chardet）
        return "utf-8"

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}")


class FetchPageTextTest(unittest.TestCase):
    """_fetch_page_text 的行为：不自动跟随重定向，每一跳都校验"""

    def test_rejects_redirect_into_private_address(self):
        """
        公网地址 302 到内网时，必须在**发起第二次请求之前**就被拒绝。
        这是 allow_redirects=False + 逐跳校验的核心价值。
        """
        requested = []

        def fake_get(url, **kwargs):
            requested.append((url, kwargs.get("allow_redirects")))
            return FakeResponse(302, {"Location": "http://127.0.0.1:8080/admin"})

        with patch.object(requests, "get", new=fake_get):
            with self.assertRaises(ValueError) as ctx:
                DetectService._fetch_page_text(f"http://{PUBLIC_IP}/start")

        self.assertIn("127.0.0.1", str(ctx.exception))
        self.assertEqual([(f"http://{PUBLIC_IP}/start", False)], requested,
                         "只应请求公网那一跳，且必须显式关闭自动重定向")

    def test_returns_extracted_text_on_success(self):
        def fake_get(url, **kwargs):
            return FakeResponse(200, {}, "<html><head><style>x{}</style></head>"
                                         "<body>事实核查正文&nbsp;内容</body></html>")

        with patch.object(requests, "get", new=fake_get):
            text = DetectService._fetch_page_text(f"https://{PUBLIC_IP}/a")

        self.assertIn("事实核查正文", text)
        self.assertIn("内容", text)
        self.assertNotIn("<body>", text, "HTML 标签应被剔除")
        self.assertNotIn("x{}", text, "style 标签内容应被剔除")

    def test_too_many_redirects_is_rejected(self):
        def fake_get(url, **kwargs):
            return FakeResponse(302, {"Location": f"http://{PUBLIC_IP}/next"})

        with patch.object(requests, "get", new=fake_get):
            with self.assertRaises(ValueError) as ctx:
                DetectService._fetch_page_text(f"http://{PUBLIC_IP}/start")

        self.assertIn("重定向次数过多", str(ctx.exception))

    def test_private_url_never_triggers_request(self):
        def fake_get(url, **kwargs):  # pragma: no cover - 不应被调用
            raise AssertionError("不应向内网地址发起请求")

        with patch.object(requests, "get", new=fake_get):
            with self.assertRaises(ValueError):
                DetectService._fetch_page_text("http://10.0.0.1/secret")


if __name__ == "__main__":
    unittest.main()
