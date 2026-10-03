"""
配置加载（P5-69）与异常捕获范围（P5-73）测试

P5-69 背景：`Settings` 原先把 `os.getenv(...)` 写在 dataclass 字段默认值里，
而默认值在**模块导入时**就求值一次——导入后再改环境变量不生效，测试只能靠改属性绕过。
现在改为实例化时读取，这里锁定该行为。

顺带锁定一个易错点：**字段名与环境变量名不一致的三个配置**
（`HOST`←`AGENT_HOST`、`PORT`←`AGENT_PORT`、`DATA_DIR`←`AGENT_DATA_DIR`）。
重构时漏掉这层映射，它们会被静默忽略——这个 bug 在本次改动中真实出现过。
"""
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from app.config import Settings, env_name_of
from app.services.detect_service import DetectService

from tests._helpers import FakeLLM, OfflineTestCase

SETTINGS_ENV_KEYS = (
    "AGENT_HOST", "AGENT_PORT", "AGENT_DATA_DIR",
    "LLM_MODEL", "LLM_TEMPERATURE", "LLM_TIMEOUT",
    "SEARCH_TOOL", "SEARCH_TOP_K", "SEARCH_TIMEOUT",
    "DETECT_TOTAL_BUDGET", "DEDUP_THRESHOLD", "INTERNAL_TOKEN",
    "EMBEDDING_MODEL",
)


class SettingsFromEnvTest(unittest.TestCase):
    """环境变量在**实例化时**读取"""

    def setUp(self):
        # 只清掉本用例关心的键，并且用 patch.dict 保证结束后自动还原
        self._saved = {k: os.environ.get(k) for k in SETTINGS_ENV_KEYS}
        for key in SETTINGS_ENV_KEYS:
            os.environ.pop(key, None)

    def tearDown(self):
        for key, value in self._saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def test_env_change_after_import_takes_effect_on_new_instance(self):
        """导入后改环境变量，新建实例必须生效（这正是 P5-69 的问题）"""
        before = Settings()
        os.environ["SEARCH_TOP_K"] = "3"
        os.environ["DEDUP_THRESHOLD"] = "0.8"
        os.environ["LLM_TEMPERATURE"] = "0.7"

        after = Settings()

        self.assertEqual(10, before.SEARCH_TOP_K, "默认值不应受影响")
        self.assertEqual(3, after.SEARCH_TOP_K)
        self.assertEqual(0.8, after.DEDUP_THRESHOLD)
        self.assertEqual(0.7, after.LLM_TEMPERATURE)

    def test_fields_with_different_env_names_are_mapped(self):
        """字段名与环境变量名不一致的三个配置必须仍能生效"""
        os.environ["AGENT_HOST"] = "0.0.0.0"
        os.environ["AGENT_PORT"] = "9001"
        os.environ["AGENT_DATA_DIR"] = "./data"

        settings = Settings()

        self.assertEqual("0.0.0.0", settings.HOST)
        self.assertEqual(9001, settings.PORT)
        self.assertTrue(str(settings.DATA_DIR).endswith("fact-agent" + os.sep + "data"))

    def test_env_name_mapping_is_declared(self):
        """映射表本身要可查（改动字段名时容易忘）"""
        self.assertEqual("AGENT_HOST", env_name_of("HOST"))
        self.assertEqual("AGENT_PORT", env_name_of("PORT"))
        self.assertEqual("AGENT_DATA_DIR", env_name_of("DATA_DIR"))
        self.assertEqual("LLM_MODEL", env_name_of("LLM_MODEL"))

    def test_relative_model_path_is_resolved_against_fact_agent(self):
        """相对路径的模型目录解析为绝对路径（否则从别的工作目录启动会找不到模型）"""
        os.environ["EMBEDDING_MODEL"] = "./models/bge_model"

        settings = Settings()

        self.assertTrue(Path(settings.EMBEDDING_MODEL).is_absolute())
        self.assertTrue(settings.EMBEDDING_MODEL.endswith("models" + os.sep + "bge_model"))

    def test_hf_model_name_is_kept_as_is(self):
        os.environ["EMBEDDING_MODEL"] = "BAAI/bge-small-zh-v1.5"

        self.assertEqual("BAAI/bge-small-zh-v1.5", Settings().EMBEDDING_MODEL)

    def test_invalid_value_falls_back_with_warning(self):
        """配置写错不应让服务起不来，但必须留下可发现的告警"""
        os.environ["SEARCH_TIMEOUT"] = "abc"

        settings = Settings()

        self.assertEqual(20, settings.SEARCH_TIMEOUT)
        self.assertTrue(any("SEARCH_TIMEOUT" in w for w in settings.warnings))

    def test_blank_value_keeps_default(self):
        os.environ["LLM_MODEL"] = ""

        self.assertEqual("qwen-plus", Settings().LLM_MODEL)

    def test_boolean_like_values_are_not_needed_but_describe_is_safe(self):
        os.environ["INTERNAL_TOKEN"] = "tok"

        described = Settings().describe()

        self.assertTrue(described["internal_auth"])
        self.assertNotIn("tok", str(described), "describe() 不应回显密钥")


class BudgetAndExceptionScopeTest(OfflineTestCase):
    """检测预算下限告警（P5-73）与异常捕获范围"""

    def build_service(self, tool) -> DetectService:
        return DetectService(tool, FakeLLM([]))

    def test_too_small_budget_is_reported_not_silently_rewritten(self):
        """预算小于下限：仍然按 5s 执行，但必须打 ERROR（原先 max(5, x) 静默改写）"""
        from app.config import settings
        from app.services.search import SearchResult

        tool = FakeBoomTool(RuntimeError("不该被调用"))
        service = DetectService(tool, FakeLLM([]))
        with patch.object(settings, "DETECT_TOTAL_BUDGET", 1):
            with self.assertLogs("app.services.detect_service", level="ERROR") as captured:
                response = service.detect("武汉又出现新型病毒了")

        self.assertTrue(any("DETECT_TOTAL_BUDGET" in line for line in captured.output),
                        "预算被下限改写时应留下 ERROR 日志")
        # 仍然返回结构化响应（不是抛异常）
        self.assertFalse(response.can_judge)
        self.assertTrue(SearchResult is not None)

    def test_unexpected_programming_error_propagates_from_search(self):
        """编程错误（AttributeError）不应被当成“搜索偶尔失败”吞掉"""
        service = self.build_service(FakeBoomTool(AttributeError("代码写错了")))
        deadline = __import__("time").monotonic() + 30

        with self.assertRaises(AttributeError):
            service._search(["查询词"], 10, deadline)

    def test_expected_failure_is_tolerated_in_search(self):
        """可预期失败（RuntimeError / 网络异常）仍然容忍：返回空结果并继续"""
        service = self.build_service(FakeBoomTool(RuntimeError("搜索服务不可用")))
        deadline = __import__("time").monotonic() + 30

        self.assertEqual([], service._search(["查询词"], 10, deadline))

    def test_expected_failure_is_tolerated_in_fetch(self):
        """抓取失败同样容忍：保留搜索摘要继续"""
        service = self.build_service(FakeBoomTool(RuntimeError("抓不到")))
        deadline = __import__("time").monotonic() + 30

        from app.services.search import SearchResult
        results = [SearchResult(title="t", url="https://example.com/a", snippet="s", source="x")]
        with patch.object(DetectService, "_fetch_page_text", side_effect=RuntimeError("抓不到")):
            enriched = service._enrich_results(results, deadline)

        self.assertEqual(1, len(enriched))
        self.assertFalse(enriched[0].content, "抓取失败时不应写入正文（保留摘要）")


class FakeBoomTool:
    """总是抛指定异常的搜索源桩"""

    def __init__(self, exc: BaseException):
        self._exc = exc

    def search(self, query, top_k, timeout=None):
        raise self._exc


if __name__ == "__main__":
    unittest.main()
