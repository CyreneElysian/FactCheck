"""
内部鉴权（P2-21）与可信度规则外置（P2-30）的测试

内部鉴权用 FastAPI TestClient 走**真实的 HTTP 层**（不是直接调函数），
因此能验证到请求头校验、401 状态码与 detail 文案这些契约。

可信度规则部分验证"外置成 JSON 之后行为不变"以及各种文件异常下的降级。
"""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.services import credibility
from app.services.credibility import load_rules

# 默认规则文件路径（仓库内）
DEFAULT_RULES = Path(__file__).resolve().parents[1] / "config" / "credibility_rules.json"


class InternalTokenTest(unittest.TestCase):
    """内部鉴权：配置了就必须带对，没配置则一律 401（fail-closed）"""

    def setUp(self):
        self.client = TestClient(app)
        self._original = settings.INTERNAL_TOKEN

    def tearDown(self):
        settings.INTERNAL_TOKEN = self._original

    def test_requests_without_token_are_rejected_when_configured(self):
        settings.INTERNAL_TOKEN = "secret-token"

        for path, body in (("/dedup", {"text": "武汉又出现新型病毒了"}),
                           ("/detect", {"text": "武汉又出现新型病毒了"}),
                           ("/index/add", {"record_id": 1, "text": "武汉又出现新型病毒了"})):
            resp = self.client.post(path, json=body)
            self.assertEqual(401, resp.status_code, f"{path} 未带令牌时必须 401")
            self.assertIn("invalid internal token", resp.text)

    def test_requests_with_wrong_token_are_rejected(self):
        settings.INTERNAL_TOKEN = "secret-token"

        resp = self.client.post("/dedup", json={"text": "武汉又出现新型病毒了"},
                                headers={"X-Internal-Token": "wrong"})

        self.assertEqual(401, resp.status_code)

    def test_requests_with_correct_token_pass_authentication(self):
        """带对令牌时不应因为鉴权被拒

        这里用 /index/add + 短文本：它会在"文本过短"处提前返回，
        从而不加载 embedding 模型（测试保持快速、离线）。
        """
        settings.INTERNAL_TOKEN = "secret-token"

        resp = self.client.post("/index/add", json={"record_id": 1, "text": "短"},
                                headers={"X-Internal-Token": "secret-token"})

        self.assertNotEqual(401, resp.status_code, "带对令牌不应被鉴权拒绝")
        self.assertEqual(200, resp.status_code)

    def test_unconfigured_token_fails_closed(self):
        """未配置令牌时拒绝所有请求：宁可明确失败，也不要静默裸奔"""
        settings.INTERNAL_TOKEN = ""

        resp = self.client.post("/dedup", json={"text": "武汉又出现新型病毒了"})

        self.assertEqual(401, resp.status_code)
        self.assertIn("not configured", resp.text)

    def test_health_is_public_and_reports_internal_auth(self):
        """健康检查本身不需要令牌（否则运维探活也要配密钥），但要把鉴权状态暴露出来"""
        settings.INTERNAL_TOKEN = "secret-token"

        resp = self.client.get("/health")

        self.assertEqual(200, resp.status_code)
        payload = resp.json()
        self.assertTrue(payload["internal_auth_enabled"])
        self.assertIn("credibility_rules", payload)


class CredibilityRulesFileTest(unittest.TestCase):
    """可信度规则外置后：默认文件可用、行为与原先一致、异常时降级"""

    def test_default_rules_file_exists_and_is_valid_json(self):
        self.assertTrue(DEFAULT_RULES.exists(), f"默认规则文件缺失：{DEFAULT_RULES}")
        raw = json.loads(DEFAULT_RULES.read_text(encoding="utf-8"))
        self.assertIn("tiers", raw)
        self.assertGreaterEqual(len(raw["tiers"]), 2)

    def test_default_rules_keep_previous_scores(self):
        """外置不改行为：官方档 0.95、主流平台 0.5、未收录 0.3"""
        rules = load_rules(DEFAULT_RULES)

        for url in ("https://www.xinhuanet.com/a", "http://wjw.wuhan.gov.cn/b",
                    "https://www.kepuchina.cn/c", "https://piyao.org.cn/d",
                    "https://www.chinacdc.cn/e"):
            self.assertEqual(0.95, rules.score(url), url)

        for url in ("https://www.zhihu.com/question/1", "https://news.qq.com/a",
                    "https://baike.baidu.com/item/x"):
            self.assertEqual(0.5, rules.score(url), url)

        for url in ("https://unknown-site.com/z", "https://example.org/a"):
            self.assertEqual(0.3, rules.score(url), url)

    def test_suffix_matching_covers_subdomains(self):
        """后缀匹配：省市子域也能命中（这正是原先 endswith 的语义）"""
        rules = load_rules(DEFAULT_RULES)

        self.assertEqual(0.95, rules.score("http://www.gov.cn/x"))
        self.assertEqual(0.95, rules.score("http://wjw.wuhan.gov.cn/b"))
        self.assertEqual(0.95, rules.score("https://sub.deep.edu.cn/y"))
        self.assertTrue(rules.is_known("https://sub.deep.edu.cn/y"))
        self.assertFalse(rules.is_known("https://unknown-site.com/z"))

    def test_exact_matching_does_not_leak_to_subdomains(self):
        """exact 是精确匹配：不应把 itsnews.cn 之类也当成 news.cn"""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rules.json"
            path.write_text(json.dumps({
                "version": 9,
                "unknown_score": 0.3,
                "tiers": [{"name": "精确档", "score": 0.9, "exact": ["news.cn"], "suffixes": []}],
            }, ensure_ascii=False), encoding="utf-8")

            rules = load_rules(path)

            self.assertEqual(0.9, rules.score("https://news.cn/a"))
            self.assertEqual(0.3, rules.score("https://www.news.cn/a"))
            self.assertEqual(9, rules.version)

    def test_custom_file_can_add_a_tier(self):
        """改词表不用改代码：自定义文件能新增档位并生效"""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rules.json"
            path.write_text(json.dumps({
                "version": 2,
                "unknown_score": 0.2,
                "tiers": [
                    {"name": "省级疾控", "score": 0.8, "suffixes": ["hbcdc.com", "scdc.cn"]},
                    {"name": "官方", "score": 0.95, "suffixes": ["gov.cn"]},
                ],
            }, ensure_ascii=False), encoding="utf-8")

            rules = load_rules(path)

            self.assertEqual(0.8, rules.score("http://www.hbcdc.com/a"))
            self.assertEqual(0.8, rules.score("https://scdc.cn/b"))
            self.assertEqual(0.95, rules.score("https://www.gov.cn/c"))
            self.assertEqual(0.2, rules.score("https://unknown.com/d"))

    def test_missing_file_falls_back_to_builtin_rules(self):
        """文件缺失不能让 Agent 起不来：回退内置规则并保持可用"""
        rules = load_rules(Path("/nonexistent/credibility_rules.json"))

        self.assertIn("内置兜底规则", rules.source)
        self.assertEqual(0.95, rules.score("https://www.xinhuanet.com/a"))
        self.assertEqual(0.3, rules.score("https://unknown-site.com/z"))

    def test_broken_file_falls_back_to_builtin_rules(self):
        """写坏 JSON 同样降级（配置是给人改的，必须假设会改错）"""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rules.json"
            path.write_text("{不是合法 JSON", encoding="utf-8")

            rules = load_rules(path)

            self.assertIn("内置兜底规则", rules.source)
            self.assertEqual(0.95, rules.score("https://www.xinhuanet.com/a"))

    def test_invalid_entries_are_skipped_not_fatal(self):
        """单条坏规则只跳过它自己，其余照常生效"""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rules.json"
            path.write_text(json.dumps({
                "version": 3,
                "unknown_score": 0.3,
                "tiers": [
                    {"name": "坏档", "score": 5, "suffixes": ["bad.com"]},
                    {"name": "空档", "score": 0.6},
                    {"name": "好档", "score": 0.9, "suffixes": ["good.com"]},
                ],
            }, ensure_ascii=False), encoding="utf-8")

            rules = load_rules(path)

            self.assertEqual(0.9, rules.score("https://good.com/a"))
            self.assertEqual(0.3, rules.score("https://bad.com/a"))

    def test_env_var_overrides_rules_file(self):
        """CREDIBILITY_RULES_FILE 可指向别的文件"""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "elsewhere.json"
            path.write_text(json.dumps({
                "version": 4,
                "unknown_score": 0.4,
                "tiers": [{"name": "自定义", "score": 0.99, "suffixes": ["my-site.cn"]}],
            }, ensure_ascii=False), encoding="utf-8")

            with patch.dict("os.environ", {"CREDIBILITY_RULES_FILE": str(path)}):
                rules = load_rules()

            self.assertEqual(0.99, rules.score("https://my-site.cn/a"))
            self.assertEqual(0.4, rules.score("https://www.xinhuanet.com/a"))

    def test_module_level_helpers_still_work(self):
        """对外的 score_source / is_known_domain 接口保持不变"""
        self.assertEqual(0.95, credibility.score_source("https://www.xinhuanet.com/a"))
        self.assertTrue(credibility.is_known_domain("https://www.gov.cn/x"))
        self.assertFalse(credibility.is_known_domain("https://unknown-site.com/z"))
        self.assertEqual(0.3, credibility.score_source(""))
        self.assertEqual(0.3, credibility.score_source("not a url"))

    def test_describe_mentions_source_and_size(self):
        text = credibility.current_rules().describe()

        self.assertIn("档", text)
        self.assertTrue(text.startswith(str(DEFAULT_RULES)) or "内置" in text)


if __name__ == "__main__":
    unittest.main()
