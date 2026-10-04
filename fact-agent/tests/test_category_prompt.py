"""
分类专属提示词的组装契约测试
================================================================

## 背景：这个功能实测后被回退了

曾为「健康」类写过一段核查重点，并用 `eval/category_ab.py` 做了 **33 条配对对照**
（同一条断言跑两次，唯一差别是 category 传不传），结论是负向的：

| 指标 | Baseline（不传） | Guided（传「健康」） |
| --- | --- | --- |
| 准确率 | 30/33 (90.9%) | 29/33 (87.9%) |
| 只它命中 | — | **0 条** |
| 只它失败 | **1 条** | — |
| 平均耗时 | 20.8s | 23.5s (+13%) |

唯一差异条目 id=39（"血液在人体内是循环流动的"，标签 TRUE）：加指导后模型**直接拒答**。

所以 `CATEGORY_GUIDANCE` 现在是**空字典**，但**整条链路保留** ——
以后想重试只需填回指导文本，不必重做管线。

## 这组测试要守两件事

1. **回退状态本身**：指导为空时，「健康」与不传分类必须给出**完全一致**的系统提示词
   （即回退是干净的，没有残留影响）。
2. **组装机制仍然可用**：往 `CATEGORY_GUIDANCE` 里塞一段指导后，确实会被追加到
   系统提示词末尾 —— 否则将来"填回去"会发现机制已经悄悄坏了。
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.prompts import detect_prompt  # noqa: E402
from app.prompts.detect_prompt import (  # noqa: E402
    CATEGORY_GUIDANCE,
    SYSTEM_PROMPT,
    build_system_prompt,
)

# 前端「消息分类」的全部取值（见 fact-check-web/src/constants/categories.ts）
FRONTEND_CATEGORIES = ["其他", "健康", "社会", "财经", "科技", "政策"]


class RollbackStateTest(unittest.TestCase):
    """当前状态：指导为空 → 所有分类都应退回通用提示词（回退必须干净）"""

    def test_guidance_is_empty_after_rollback(self):
        """
        记录回退决定。

        如果将来有人重新启用某个分类，这条测试会失败 —— 那是**有意的**：
        它提醒你重新启用前必须先用 eval/category_ab.py 跑对照，
        并把结论更新到本文件与 detect_prompt 的注释里。
        """
        self.assertEqual(
            CATEGORY_GUIDANCE, {},
            "CATEGORY_GUIDANCE 当前应为空（「健康」经 33 条对照验证为负向，已回退）。"
            "重新启用前请先跑 eval/category_ab.py --all 并更新结论。",
        )

    def test_all_frontend_categories_fall_back_to_generic(self):
        """回退后，前端所有分类取值都必须与"不传分类"给出同一份系统提示词"""
        for category in FRONTEND_CATEGORIES:
            with self.subTest(category=category):
                self.assertEqual(build_system_prompt(category), SYSTEM_PROMPT)

    def test_none_and_empty_and_whitespace(self):
        for value in (None, "", "   "):
            with self.subTest(value=repr(value)):
                # 空白串会走 strip() 后查表，结果仍是通用提示词
                self.assertEqual(build_system_prompt(value), SYSTEM_PROMPT)

    def test_unknown_category_is_safe(self):
        """未知分类不能抛异常，也不能改变提示词（分类写错≠检测做不了）"""
        for value in ("不存在的分类", "health", "健康类", "健康 "):
            with self.subTest(value=value):
                self.assertIsInstance(build_system_prompt(value), str)

    def test_never_returns_shorter_than_generic(self):
        """
        无论哪种分类，返回的提示词都**不能短于**通用提示词 ——
        否则说明实现把注入防护或输出契约弄丢了（追加式设计不该发生这种事）。
        """
        for value in [None, *FRONTEND_CATEGORIES, "未知"]:
            with self.subTest(value=value):
                self.assertGreaterEqual(len(build_system_prompt(value)), len(SYSTEM_PROMPT))


class AssemblyMechanismTest(unittest.TestCase):
    """
    组装机制本身（与"当前有没有指导"无关）。

    用临时注入的指导来测：这样即使 CATEGORY_GUIDANCE 是空的，
    也能验证"填回去以后确实会被追加"这个机制没有坏。
    """

    def setUp(self):
        self._saved = dict(CATEGORY_GUIDANCE)

        def restore():
            CATEGORY_GUIDANCE.clear()
            CATEGORY_GUIDANCE.update(self._saved)

        self.addCleanup(restore)

    def test_guidance_is_appended_to_generic_prompt(self):
        CATEGORY_GUIDANCE["测试类"] = "\n【测试用指导】只用于验证追加机制\n"

        result = build_system_prompt("测试类")

        self.assertTrue(result.startswith(SYSTEM_PROMPT),
                        "必须在通用提示词**之后**追加，不能替换掉它")
        self.assertIn("【测试用指导】", result)
        self.assertEqual(len(result), len(SYSTEM_PROMPT) + len(CATEGORY_GUIDANCE["测试类"]))

    def test_guidance_lookup_strips_whitespace(self):
        CATEGORY_GUIDANCE["测试类"] = "\n【X】\n"
        self.assertIn("【X】", build_system_prompt("  测试类  "))

    def test_other_categories_unaffected(self):
        """只给一个分类加指导，不能影响其它分类（避免"全局污染"）"""
        CATEGORY_GUIDANCE["测试类"] = "\n【只给测试类】\n"

        self.assertIn("【只给测试类】", build_system_prompt("测试类"))
        for other in ("健康", "社会", "其他", None):
            with self.subTest(other=other):
                self.assertNotIn("【只给测试类】", build_system_prompt(other))

    def test_untrusted_boundary_kept_intact(self):
        """
        追加指导**不得**动到注入防护与输出契约那部分 ——
        这是"只追加、不替换"这个设计取舍要保住的东西。
        """
        CATEGORY_GUIDANCE["测试类"] = "\n【附加】\n"
        result = build_system_prompt("测试类")

        for must_keep in ["<untrusted_claim>", "<untrusted_evidence>", "不要输出任何其他文字",
                          "忽略以上设定"]:
            self.assertIn(must_keep, result, f"追加指导后丢失了安全约束：{must_keep}")


class UserTemplateTest(unittest.TestCase):
    """分类指导不应该出现在 user 消息模板里（它是系统指令，不是不可信数据）"""

    def test_user_template_has_no_guidance_placeholder(self):
        self.assertNotIn("guidance", detect_prompt.USER_PROMPT_TEMPLATE)
        self.assertNotIn("category", detect_prompt.USER_PROMPT_TEMPLATE)

    def test_untrusted_content_is_delimited_in_user_template(self):
        """
        user 消息里的不可信内容必须包在标签内。

        注意：`<untrusted_claim>` 出现在**系统提示词**里是正确且必要的
        （那里在声明"这些标签内是数据、不是指令"）；这里验证的是
        **user 模板**确实用这些标签把内容框起来。
        """
        template = detect_prompt.USER_PROMPT_TEMPLATE
        self.assertIn("<untrusted_claim>", template)
        self.assertIn("</untrusted_claim>", template)
        self.assertIn("<untrusted_evidence>", template)
        self.assertIn("</untrusted_evidence>", template)
        # 待检测文本与证据都必须落在标签**内部**
        self.assertLess(template.index("<untrusted_claim>"), template.index("{text}"))
        self.assertLess(template.index("{text}"), template.index("</untrusted_claim>"))
        self.assertLess(template.index("<untrusted_evidence>"), template.index("{evidence}"))
        self.assertLess(template.index("{evidence}"), template.index("</untrusted_evidence>"))

    def test_system_prompt_declares_the_tags_as_data(self):
        """系统提示词必须明确声明这些标签内是数据（否则边界只写在 user 侧没用）"""
        self.assertIn("<untrusted_claim>", SYSTEM_PROMPT)
        self.assertIn("数据", SYSTEM_PROMPT)


if __name__ == "__main__":
    unittest.main(verbosity=2)
