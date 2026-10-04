"""
网页编码探测的回归测试（detect_service._decode_response）
================================================================

## 为什么要有这组测试

真实踩到的缺陷：抓取网页正文时用的是 `requests` 的 `resp.text`。
`requests` 在响应头**没有声明 charset** 时，对 text/* 会按 RFC 2616 的历史遗留
兜底成 **ISO-8859-1**；而国内不少站点（含 `health.people.com.cn` 的部分页面）
是 **GBK** 编码且未声明 charset → 中文全部变乱码。

后果非常隐蔽：
  * `health.people.com.cn` 命中可信度规则 **0.95**（代码里的权威域名），
    但它进提示词的正文是乱码 → **最权威的那条证据，模型完全读不到**；
  * 乱码还会被写进 `t_evidence.snippet` 落库，前端展示也是乱码；
  * 表面上看一切正常（HTTP 200、length 非 0、证据条数正常），
    只有人去读日志里的 SQL 参数才发现。

另一个关键事实（决定了修复必须自己探测编码，而不能指望 apparent_encoding）：
    本项目的 venv 里 **chardet 不可用**，而 requests 2.34.2 的
    `apparent_encoding` 在缺少 chardet 时**直接返回 "utf-8"**（见其源码）——
    所以把编码判断交给 requests 是**必然**失败，不是"偶尔"失败。
"""

import io
import os
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.detect_service import (  # noqa: E402
    _FALLBACK_ENCODINGS,
    _decode_response,
    _repair_mojibake,
)

# 一段同时含中文与英文的页面正文，用来判断解码是否正确
PAGE = (
    "<html><head><title>熬夜属于2A类致癌物</title></head>"
    "<body><h1>熬夜属于2A类致癌物 睡眠是必需品不是奢侈品</h1>"
    "<p>世界卫生组织下属的国际癌症研究机构将昼夜节律紊乱列为2A类致癌物。</p>"
    "</body></html>"
)
MARKER = "熬夜属于2A类致癌物"


def make_response(body: bytes, declared=None, apparent=None):
    """
    构造一个**如实模拟**的响应对象。

    关键：`apparent_encoding` 默认不接 charset_normalizer，而是复刻
    requests 在**没有 chardet** 时的真实行为（返回 "utf-8"），
    否则测试会走一条生产环境里不存在的路径，变成自欺。
    """
    resp = Mock()
    resp.content = body
    resp.encoding = declared
    if apparent is None:
        resp.apparent_encoding = "utf-8"   # requests 缺 chardet 时的真实返回值
    else:
        resp.apparent_encoding = apparent
    return resp


class DecodeResponseTest(unittest.TestCase):
    """按真实编码解码，而不是按请求头猜测"""

    def test_gbk_page_without_charset_is_decoded_correctly(self):
        """核心回归：GBK 页面 + requests 的 ISO-8859-1 兜底 → 必须解出正确中文"""
        resp = make_response(PAGE.encode("gbk"), declared="ISO-8859-1")
        self.assertIn(MARKER, _decode_response(resp))

    def test_gbk_page_with_none_declared(self):
        """响应头完全没给编码"""
        resp = make_response(PAGE.encode("gbk"), declared=None)
        self.assertIn(MARKER, _decode_response(resp))

    def test_utf8_page_declared_as_iso_8859_1(self):
        """UTF-8 页面被兜底成 ISO-8859-1 时也要正确"""
        resp = make_response(PAGE.encode("utf-8"), declared="ISO-8859-1")
        self.assertIn(MARKER, _decode_response(resp))

    def test_explicit_charset_is_trusted(self):
        """请求头**显式**声明编码时应优先采信（明示优先于统计猜测）"""
        resp = make_response(PAGE.encode("gbk"), declared="gbk")
        self.assertIn(MARKER, _decode_response(resp))

        resp2 = make_response(PAGE.encode("utf-8"), declared="utf-8")
        self.assertIn(MARKER, _decode_response(resp2))

    def test_explicit_charset_with_underscore(self):
        """部分站点写成 gb2312 / GB_2312 这类带下划线或别名的写法"""
        resp = make_response(PAGE.encode("gb2312"), declared="GB_2312")
        self.assertIn(MARKER, _decode_response(resp))

    def test_bogus_declared_charset_falls_back_to_detection(self):
        """声明了一个不存在的编码名，不能抛异常，要回落探测"""
        resp = make_response(PAGE.encode("gbk"), declared="definitely-not-an-encoding")
        out = _decode_response(resp)
        self.assertIsInstance(out, str)
        self.assertIn(MARKER, out)

    def test_never_raises_on_garbage(self):
        """任意字节都不能让解码抛异常（拿不准编码不该让整次检测失败）"""
        for body in [b"", b"\xff\xfe\x00\x01", bytes(range(256)), b"\x80\x81\x82"]:
            with self.subTest(body=body[:8]):
                out = _decode_response(make_response(body, declared="ISO-8859-1"))
                self.assertIsInstance(out, str)

    def test_works_without_charset_normalizer(self):
        """
        charset_normalizer 不可用时仍要能解对：
        此时应回落到 apparent_encoding（测试里模拟为真实的 gbk 探测结果）。
        """
        real_import = __builtins__["__import__"] if isinstance(__builtins__, dict) else __import__

        def fake_import(name, *args, **kwargs):
            if name == "charset_normalizer" or name.startswith("charset_normalizer."):
                raise ImportError("模拟未安装 charset_normalizer")
            return real_import(name, *args, **kwargs)

        resp = make_response(PAGE.encode("gbk"), declared="ISO-8859-1", apparent="gbk")
        with patch("builtins.__import__", side_effect=fake_import):
            out = _decode_response(resp)
        self.assertIn(MARKER, out)

    def test_fallback_encoding_set_covers_requests_defaults(self):
        """
        兜底编码集合必须覆盖 requests 会塞的那些值，
        否则它们会被误当成"显式声明"而直接采信，缺陷就会复活。
        """
        for enc in ["ISO-8859-1", "iso-8859-1", "latin-1", "ascii", "cp1252"]:
            self.assertIn(enc.lower(), _FALLBACK_ENCODINGS, f"{enc} 必须在兜底集合里")


class RepairMojibakeTest(unittest.TestCase):
    """
    兜底：模型/上游接口给出的标题若已被单字节编码误读，尝试修回来。

    与 _decode_response 的分工：
      * `_decode_response` 管**我们自己抓的响应字节**——这是主防线；
      * `_repair_mojibake` 管**别人已经解码错的字符串**（LLM 输出、搜索接口返回）——只是兜底。

    最后一条用例记录**修不了**的形态，避免以后误以为这个兜底能救所有乱码。
    """

    def test_typical_latin1_misread_is_repaired(self):
        """最典型形态：UTF-8 字节被 latin-1 逐字节读 → 可还原"""
        correct = "专家：熬夜属于2A类致癌物 睡眠是必需品不是奢侈品"
        broken = correct.encode("utf-8").decode("latin-1")

        self.assertNotIn("专", broken)          # 确认确实是乱码
        self.assertEqual(_repair_mojibake(broken, "test"), correct)

    def test_normal_chinese_is_left_alone(self):
        """本来就正常的中文不能被乱改"""
        for s in ["熬夜属于2A类致癌物", "健康·生活--人民网", "plain ascii text"]:
            self.assertEqual(_repair_mojibake(s, "test"), s)

    def test_empty_and_none_safe(self):
        self.assertEqual(_repair_mojibake("", "test"), "")
        self.assertIsNone(_repair_mojibake(None, "test"))

    def test_gbk_misread_as_latin1_is_repaired(self):
        """GBK 页面被当 latin-1 读：字节本身还在，用 gb18030 能救回"""
        correct = "熬夜属于2A类致癌物"
        broken = correct.encode("gbk").decode("latin-1")

        self.assertEqual(_repair_mojibake(broken, "test"), correct)

    def test_double_corrupted_string_is_returned_unchanged(self):
        """
        ⚠️ 记录能力边界：**双重损坏**修不回来。

        这是 recordId=27 那条 t_evidence.title 的真实字节：
            D7 A8 D2 A3 D2 B9 32 41 C2 B0 C2 A0 CB AF C7 B1 C6 B7 DD B3 C6 B7 2D 2D 2D 2D
        其中 D7A8/D2A3/D2B9 是原始 GBK 字节，而 C2B0/C2A0 却是单字节 B0/A0 被 UTF-8 化的
        产物 —— 同一条串里混了两种损坏层级，不存在统一还原路径。

        所以这个兜底**不能**替代"在产生乱码的那一环之前修好"，
        它只能处理单一层级的误读，并在修不了时打 WARN 留下线索。
        """
        corrupt = bytes.fromhex(
            "D7A8D2A3D2B93241C2B0C2A0CBAFC7B1C6B7DDB3C6B72D2D2D2D"
        ).decode("utf-8")

        out = _repair_mojibake(corrupt, "test")

        # 不能崩，也不能把数据改得更糟；但必须承认救不回来
        self.assertIsInstance(out, str)
        self.assertEqual(out, corrupt)


class FetchPageTextEncodingTest(unittest.TestCase):
    """端到端：真正的抓取路径也要解对（防止只在单测里对）"""

    def _run_fetch(self, body: bytes, declared):
        from app.services import detect_service as ds

        resp = Mock()
        resp.content = body
        resp.encoding = declared
        resp.apparent_encoding = "utf-8"
        resp.headers = {}
        resp.status_code = 200
        resp.raise_for_status = Mock()

        with patch.object(ds, "_assert_url_allowed", return_value=None), \
             patch.object(ds.requests, "get", return_value=resp):
            return ds.DetectService._fetch_page_text("http://example.com/a")

    def test_fetch_path_decodes_gbk_page(self):
        text = self._run_fetch(PAGE.encode("gbk"), "ISO-8859-1")
        self.assertIn(MARKER, text)
        # 标签应已被剥离
        self.assertNotIn("<h1>", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
