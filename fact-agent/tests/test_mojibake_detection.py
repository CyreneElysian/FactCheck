"""
乱码识别与"用真实页面标题替换"的回归测试
================================================================

## 真实故障链（recordId=27，实测）

1. Tavily 对 `health.people.com.cn`（**gb2312** 页面）返回的 title/snippet **本身就是乱码**：
       title   : רңҹ2A° ˯ǱƷݳƷ----
       snippet : ݡ桷2022й̬ݿ⣬о2022ڵ˯״ʾ2022꣬...
2. LLM 把这个乱码标题原样抄进 evidence → 落库 → 前端展示乱码。
3. 而我**自己抓同一个 URL 是干净的**（`_decode_response` 已修好编码）。

所以修复方向不是"还原那个坏字符串"（它已双重损坏、不可逆：混着被 UTF-8 化的单字节，
连 latin-1 都编不回去），而是**用我们抓到的真实标题替换它**。

## 两个判据上的教训（都写在测试里）

* **不能只看 U+0080–U+00FF**：那段乱码里是**希伯来字母 ר(U+05E8)**、
  **西里尔字母 ң(U+04A3)**，都不在 latin-1 补充区。
* **不能"有汉字就算正常"**：那段坏摘要里恰好混进 2 个形似汉字的字符
  （`桷` U+6877），最初就是这么漏判的。
  正确判据是**非 ASCII 字符里汉字的占比**（正常中文 > 0.7，实测乱码 ≈ 0.04）。
"""

import os
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.detect_service import (  # noqa: E402
    DetectService,
    _extract_html_title,
    _looks_mojibake,
)
from tests._helpers import OfflineTestCase  # noqa: E402

# 真实从 Tavily 拿到的坏标题 / 坏摘要（字节原样）
BAD_TITLE = bytes.fromhex(
    "D7A8D2A3D2B93241C2B0C2A0CBAFC7B1C6B7DDB3C6B72D2D2D2D"
).decode("utf-8")
BAD_SNIPPET = (
    "ݡ桷2022й̬ݿ⣬о2022ڵ˯״ʾ2022ܷ꣬ÿƽ˯ʱΪ7.40Сʱ"
    "ܷÿƽ˯ʱ8Сʱ16.79%ܷÿƽ˯ʱ7Сʱ桷ΪͲͬȺ˯״ԣڵ˯ʱдӡ˯д"
)


class LooksMojibakeTest(unittest.TestCase):
    """乱码判据：按"非 ASCII 里汉字的占比"判断"""

    def test_real_tavily_garbage_is_detected(self):
        """真实故障数据必须被判为乱码（这两条就是漏判过的）"""
        self.assertTrue(_looks_mojibake(BAD_TITLE))
        self.assertTrue(_looks_mojibake(BAD_SNIPPET))

    def test_bad_snippet_contains_cjk_lookalikes(self):
        """
        记录漏判原因：这段乱码里确实混着形似汉字的字符，
        所以"有汉字就算正常"的判据会失效 —— 必须是比例判据。
        """
        cjk = [ch for ch in BAD_SNIPPET if "\u4e00" <= ch <= "\u9fff"]
        self.assertGreater(len(cjk), 0, "这段乱码里确实有汉字区字符，这正是当初漏判的原因")
        self.assertTrue(_looks_mojibake(BAD_SNIPPET), "但仍必须被判为乱码")

    def test_latin1_misread_is_detected(self):
        """典型 UTF-8 被 latin-1 误读（高位字符在 latin-1 补充区）"""
        broken = "专家：熬夜属于2A类致癌物".encode("utf-8").decode("latin-1")
        self.assertTrue(_looks_mojibake(broken))

    def test_normal_text_is_not_flagged(self):
        """正常内容不能被误判 —— 误判的代价是把好标题换成抓取结果"""
        normals = [
            "熬夜被归为2A类致癌物，谨记15个必须休息的信号！_大脑",
            "这份《报告》基于2022年中国社会心态调查数据库，研究分析了2022年民众的睡眠状况。",
            "专家：熬夜属于2A类致癌物 睡眠是必需品不是奢侈品--健康·生活--人民网",
            "IARC 将昼夜节律紊乱列为 2A 类致癌物",
            "Article | Health Knowledge",
            "2022 2023 2024",
            "がん予防のための生活習慣",          # 日文（汉字占比适中，不该被误判）
            "",
        ]
        for s in normals:
            with self.subTest(text=s[:24]):
                self.assertFalse(_looks_mojibake(s), f"误判为乱码：{s[:40]!r}")

    def test_short_ascii_is_not_flagged(self):
        for s in ["ok", "A", "123", "-"]:
            self.assertFalse(_looks_mojibake(s))


class ExtractHtmlTitleTest(unittest.TestCase):
    """标题提取：必须能处理真实页面里的写法"""

    def test_extracts_and_normalizes(self):
        html = "<html><head><title>  专家：熬夜属于2A类致癌物\n  睡眠是必需品  </title></head><body>x</body></html>"
        self.assertEqual(_extract_html_title(html), "专家：熬夜属于2A类致癌物 睡眠是必需品")

    def test_decodes_common_entities(self):
        html = "<title>A&amp;B&nbsp;C</title>"
        self.assertEqual(_extract_html_title(html), "A&B C")

    def test_case_insensitive_and_attributes(self):
        html = '<TITLE lang="zh-CN">熬夜致癌吗</TITLE>'
        self.assertEqual(_extract_html_title(html), "熬夜致癌吗")

    def test_missing_title_returns_empty(self):
        self.assertEqual(_extract_html_title("<html><body>没有标题</body></html>"), "")


class FetchReturnsTitleTest(unittest.TestCase):
    """_fetch_page_text 现在同时返回标题与正文"""

    def _fetch(self, body: bytes, declared=None):
        resp = Mock()
        resp.content = body
        resp.encoding = declared
        resp.apparent_encoding = "utf-8"
        resp.headers = {}
        resp.status_code = 200
        resp.raise_for_status = Mock()

        with patch("app.services.detect_service._assert_url_allowed", return_value=None), \
             patch("app.services.detect_service.requests.get", return_value=resp):
            return DetectService._fetch_page_text("http://example.com/a")

    def test_returns_title_and_text_for_gbk_page(self):
        """gb2312 页面（人民网那种）：标题与正文都要正确解出"""
        html = ("<html><head><title>专家：熬夜属于2A类致癌物</title></head>"
                "<body><p>世界卫生组织下属的国际癌症研究机构将昼夜节律紊乱列为2A类致癌物。</p>"
                "</body></html>")
        title, text = self._fetch(html.encode("gb18030"), declared="ISO-8859-1")

        self.assertEqual(title, "专家：熬夜属于2A类致癌物")
        self.assertIn("昼夜节律紊乱", text)
        # 标题不应残留在正文里（已在剥标签前取出）
        self.assertNotIn("<title>", text)

    def test_returns_empty_title_when_absent(self):
        title, text = self._fetch("<html><body>只有正文</body></html>".encode("utf-8"))
        self.assertEqual(title, "")
        self.assertIn("只有正文", text)


class EnrichReplacesBadTitleTest(OfflineTestCase):
    """端到端：搜索结果的乱码标题被真实页面标题替换"""

    def _service(self):
        """
        构造只用于 `_enrich_results` 的服务实例。

        注意：`_enrich_results` 只抓前 `settings.SEARCH_FETCH_TOP` 条，
        所以用例里必须把它调大，否则条目根本不会被抓取 —— 替换逻辑也就无从触发
        （这个前提我最初漏了，测试因此失败）。
        """
        self.patch_setting("SEARCH_FETCH_TOP", 10)
        return DetectService(search_tool=Mock(), llm=Mock())

    @staticmethod
    def _deadline() -> int:
        """
        造一个"预算充足"的 deadline。

        `_remaining()` 内部是 `math.ceil(deadline - time.monotonic())`，
        传 `float("inf")` 会抛 OverflowError（ceil 要转 int），所以用足够大的有限值。
        """
        import time

        return int(time.monotonic()) + 3600

    def test_bad_title_replaced_and_bad_snippet_dropped(self):
        from app.services import detect_service as ds
        from app.services.search import SearchResult

        item = SearchResult(
            title=BAD_TITLE,
            url="http://health.people.com.cn/n1/2023/0319/c14739-32646993.html",
            snippet=BAD_SNIPPET,
            source="health.people.com.cn",
        )
        real_title = "专家：熬夜属于2A类致癌物 睡眠是必需品不是奢侈品--健康·生活--人民网"

        with patch.object(ds.DetectService, "_fetch_page_text",
                          return_value=(real_title, "世界卫生组织……")):
            out = self._service()._enrich_results([item], deadline=self._deadline())

        self.assertEqual(out[0].title, real_title, "乱码标题必须被真实标题替换")
        self.assertEqual(out[0].snippet, "", "乱码摘要必须被丢弃，避免被模型抄进证据")
        self.assertIn("世界卫生组织", out[0].content)

    def test_good_title_is_kept(self):
        """正常标题不能被替换（抓取到的标题可能带站点后缀，不应覆盖更精确的原标题）"""
        from app.services import detect_service as ds
        from app.services.search import SearchResult

        item = SearchResult(title="熬夜被归为2A类致癌物，谨记15个必须休息的信号！_大脑",
                            url="http://example.com/a", snippet="正常摘要", source="example.com")

        with patch.object(ds.DetectService, "_fetch_page_text",
                          return_value=("example.com 站点标题", "正文")):
            out = self._service()._enrich_results([item], deadline=self._deadline())

        self.assertEqual(out[0].title, "熬夜被归为2A类致癌物，谨记15个必须休息的信号！_大脑")
        self.assertEqual(out[0].snippet, "正常摘要")


if __name__ == "__main__":
    unittest.main(verbosity=2)
