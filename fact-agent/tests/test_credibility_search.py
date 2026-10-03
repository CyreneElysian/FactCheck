"""
app/services/credibility.py 与 app/services/search.py 行为锁定测试

全部离线：搜索源工厂只构造对象，不发起任何请求（dummy 为空实现，
tavily/serpapi/bocha 的构造函数只存 api_key）。
"""
import unittest

from app.services import credibility
from app.services.credibility import (
    DEFAULT_UNKNOWN_SCORE,
    is_known_domain,
    score_source,
)
from app.services.search import (
    DummySearchTool,
    SearchTool,
    TavilySearchTool,
    describe_tool,
    get_search_tool,
    is_offline_tool,
    validate_tool_config,
)

from tests._helpers import OfflineTestCase


class TestCredibilityScoring(OfflineTestCase):

    def test_score_source_tiers(self):
        """锁定行为：可信度按"权威 0.95 / 一般 0.5 / 未收录 0.3"三档返回"""
        self.assertEqual(0.95, score_source("https://www.xinhuanet.com/a"))
        self.assertEqual(0.95, score_source("https://wjw.wuhan.gov.cn/b"))
        self.assertEqual(0.5, score_source("https://www.zhihu.com/d"))
        self.assertEqual(0.3, score_source("https://some-unknown-site.com/e"))
        self.assertEqual(0.3, score_source(""))

    def test_gov_cn_suffix_matches_subdomains(self):
        """锁定行为：.gov.cn 按后缀匹配，任意层级的政府子域都算权威来源"""
        for url in ("https://gov.cn/a",
                    "https://www.gov.cn/a",
                    "https://cdc.beijing.gov.cn/a"):
            self.assertEqual(0.95, score_source(url), url)

    def test_lookalike_domain_is_not_high_credibility(self):
        """锁定行为：后缀匹配不会把伪装域名（evilgov.cn / gov.cn.attacker.com）误判为权威"""
        self.assertEqual(DEFAULT_UNKNOWN_SCORE, score_source("https://evilgov.cn/a"))
        self.assertEqual(DEFAULT_UNKNOWN_SCORE, score_source("https://gov.cn.attacker.com/a"))
        self.assertEqual(DEFAULT_UNKNOWN_SCORE, score_source("https://notxinhuanet.com/a"))

    def test_score_source_is_case_insensitive_on_domain(self):
        """锁定行为：域名大小写不影响评分（netloc 先 lower）"""
        self.assertEqual(0.95, score_source("https://WWW.XINHUANET.COM/A"))

    def test_score_source_is_deterministic(self):
        """锁定行为：同一 URL 多次调用结果完全一致（可审计，模型无法影响）"""
        url = "https://www.xinhuanet.com/a"
        self.assertEqual({score_source(url)}, {score_source(url), score_source(url)})

    def test_score_source_handles_garbage_input(self):
        """锁定行为：非 URL / 非字符串输入不抛异常，一律落到未收录分值"""
        for value in ("not a url", "http://", "://bad", None, 12345):
            self.assertEqual(DEFAULT_UNKNOWN_SCORE, score_source(value))

    def test_is_known_domain(self):
        """锁定行为：is_known_domain 以未收录分值为阈值判断域名是否已收录"""
        self.assertTrue(is_known_domain("https://www.xinhuanet.com/a"))
        self.assertTrue(is_known_domain("https://www.zhihu.com/d"))
        self.assertTrue(is_known_domain("https://wjw.wuhan.gov.cn/b"))
        self.assertFalse(is_known_domain("https://some-unknown-site.com/e"))
        self.assertFalse(is_known_domain(""))
        self.assertFalse(is_known_domain("https://evilgov.cn/a"))

    def test_domain_tables_are_consistent(self):
        """锁定行为：规则表本身无重复、无空串，且各档域名不会互相覆盖

        P2-30 之后规则来自 config/credibility_rules.json，因此这里改为遍历
        当前生效的规则对象（而不是模块里的常量）。
        """
        tiers = credibility.current_rules().tiers
        self.assertGreaterEqual(len(tiers), 2)

        seen = {}
        for tier in tiers:
            entries = list(tier.exact) + list(tier.suffixes)
            self.assertEqual(len(entries), len(set(entries)),
                             f"{tier.name} 档内不应有重复项")
            for entry in entries:
                self.assertNotEqual("", entry, "规则表不应有空串")
                self.assertNotIn(entry, seen,
                                 f"域名 {entry} 同时出现在「{seen.get(entry)}」与「{tier.name}」两档")
                seen[entry] = tier.name


class TestSearchToolFactory(OfflineTestCase):

    def test_dummy_is_offline_and_real_tools_are_not(self):
        """锁定行为：is_offline_tool 只把 DummySearchTool 判为不联网"""
        self.assertIsInstance(get_search_tool("dummy", ""), DummySearchTool)
        self.assertTrue(is_offline_tool(get_search_tool("dummy", "")))
        self.assertFalse(is_offline_tool(get_search_tool("tavily", "k")))
        self.assertFalse(is_offline_tool(get_search_tool("serpapi", "k")))
        self.assertFalse(is_offline_tool(get_search_tool("bocha", "k")))
        self.assertFalse(is_offline_tool(None))

    def test_factory_builds_expected_types_and_keeps_api_key(self):
        """锁定行为：工厂按名称返回对应实现，并把 api_key 透传给联网搜索源"""
        tavily = get_search_tool("tavily", "secret")
        self.assertIsInstance(tavily, TavilySearchTool)
        self.assertEqual("secret", tavily.api_key)
        self.assertTrue(isinstance(tavily, SearchTool))

    def test_factory_is_case_insensitive_and_defaults_to_dummy(self):
        """锁定行为：名称大小写不敏感；空名称退化为 dummy（不联网）"""
        self.assertIsInstance(get_search_tool("TAVILY", "k"), TavilySearchTool)
        self.assertIsInstance(get_search_tool("", "k"), DummySearchTool)
        self.assertIsInstance(get_search_tool(None, "k"), DummySearchTool)

    def test_unknown_name_degrades_to_dummy_without_exception(self):
        """锁定行为：未知搜索源名称退化为 Dummy，不抛异常（启动不被配置错误卡死）"""
        tool = get_search_tool("不存在的名字", "k")
        self.assertIsInstance(tool, DummySearchTool)
        self.assertTrue(is_offline_tool(tool))

    def test_describe_tool(self):
        """锁定行为：describe_tool 返回类名，None 安全处理（供 /health 展示）"""
        self.assertEqual("DummySearchTool", describe_tool(get_search_tool("dummy", "")))
        self.assertEqual("TavilySearchTool", describe_tool(get_search_tool("tavily", "k")))
        self.assertEqual("None", describe_tool(None))

    def test_dummy_search_returns_empty_without_network(self):
        """锁定行为：DummySearchTool.search 直接返回空列表（不联网）"""
        self.assertEqual([], get_search_tool("dummy", "").search("任意查询", 10))


class TestValidateToolConfig(OfflineTestCase):

    def test_dummy_needs_no_key(self):
        """锁定行为：dummy 不需要 API Key，校验通过（返回 None）"""
        self.assertIsNone(validate_tool_config("dummy", ""))
        self.assertIsNone(validate_tool_config("", ""))
        self.assertIsNone(validate_tool_config(None, ""))

    def test_missing_api_key_is_reported(self):
        """锁定行为：联网搜索源缺少 SEARCH_API_KEY 时返回含 "SEARCH_API_KEY" 的告警"""
        for name in ("tavily", "serpapi", "bocha"):
            message = validate_tool_config(name, "")
            self.assertIsNotNone(message, name)
            self.assertIn("SEARCH_API_KEY", message)

    def test_configured_key_passes(self):
        """锁定行为：联网搜索源配好 Key 后校验通过"""
        for name in ("tavily", "serpapi", "bocha"):
            self.assertIsNone(validate_tool_config(name, "k"), name)

    def test_unknown_name_is_reported(self):
        """锁定行为：未知搜索源返回含"未知搜索源"的告警（提示会退化为 dummy）"""
        message = validate_tool_config("不存在的名字", "k")
        self.assertIsNotNone(message)
        self.assertIn("未知搜索源", message)
        self.assertIn("dummy", message)


if __name__ == "__main__":
    unittest.main()
