"""
「不可证伪断言」提示词改动的**回退留档**测试
================================================================

## 结论：改动实测为负向，已回退

曾经给系统提示词加过两条规则（第 6/7 条），目的是修「模型把不可证伪的断言判成假」：

    6. 不要把「找不到证据」当成「假」（诉诸无知）
    7. 遇到不可证伪的断言时判 UNVERIFIED（并排除"与公认科学共识相矛盾"的情形）

随后用 `eval/unverified_ab.py` 在**全部 60 条**样本上做配对对照（同一条断言跑两次，
唯一差别是这两条规则加不加），结论是**负向**的：

| 指标 | before | after |
| --- | --- | --- |
| 总准确率 | 52/60 (86.7%) | 52/60 (86.7%) |
| **UNVERIFIED 命中** | **9/15** | **8/15** ← 变差 |
| 被误判成 UNVERIFIED（回归面） | 1 | 1 |
| McNemar p | — | 1.0000 |

**改动恰好产生了我预警过的那个退化** —— 修好 3 条、弄坏 3 条，净收益为零：

| 修好 | 弄坏 |
| --- | --- |
| id=19 洗完头不吹干睡觉得面瘫：拒答 → FALSE | id=18 长痘因吃辣：FALSE → UNVERIFIED |
| id=24 手机信号差辐射大：UNVERIFIED → FALSE | id=53 某国在外星建基地：变成拒答 |
| id=46 拍到不明飞行物：TRUE → UNVERIFIED | id=56 手机电池爆炸风险：变成拒答 |

## 更重要的：我最初对"根因"的判断是错的

原先的结论来自**偏差样本**：`eval/category_ab.py` 只跑「健康」类 33 条，
其中恰好只有 3 条 UNVERIFIED，看起来像"几乎全部判错"。
扩大到全部 60 条后，基线在 15 条 UNVERIFIED 上**命中 9/15** —— 多数本来就判对，
而那 3 条健康类里也有 2 条（id=54/59）基线就是对的。**不存在"系统性偏错"。**

## 这组测试的作用

1. **记录回退状态**：规则 6/7 不在提示词里；
2. **防止有人不看评测就加回**：如果重新启用，这些测试会失败，提醒先跑
   `python eval/unverified_ab.py --all` 并更新本文件与 `detect_prompt` 的注释；
3. **守住不该被顺手改坏的东西**：JSON 输出契约、注入防护、规则 1~5 的位置。
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.prompts import detect_prompt  # noqa: E402
from app.prompts.detect_prompt import SYSTEM_PROMPT  # noqa: E402


class RollbackStateTest(unittest.TestCase):
    """规则 6/7 已回退，不应出现在提示词里"""

    def test_unfalsifiable_rules_are_absent(self):
        """
        记录回退决定。

        若这条失败，说明有人把规则 6/7 加回来了 —— 那是**有意的**提醒：
        重新启用前必须先跑 `eval/unverified_ab.py --all` 做对照，
        并把结论更新到本文件与 detect_prompt 的注释里（该改动实测负向，见文件头）。
        """
        for marker in ("诉诸无知", "判定准则", "公认科学共识"):
            self.assertNotIn(
                marker, SYSTEM_PROMPT,
                f"系统提示词里出现了「{marker}」——规则 6/7 似乎被加回来了。"
                "该改动经 60 条配对对照实测为负向（UNVERIFIED 9/15→8/15），"
                "重新启用前请先跑 eval/unverified_ab.py --all 并更新结论。",
            )

    def test_system_prompt_only_carries_injection_rules(self):
        """系统提示词的职责是注入防护；判定规则在 user 模板里"""
        self.assertIn("【不可信输入规则】", SYSTEM_PROMPT)
        for i in range(1, 5):
            self.assertIn(f"\n{i}. ", SYSTEM_PROMPT, f"注入防护缺少第 {i} 条")
        # 不该出现第 5 条及以后（规则 1~5 属于 user 模板）
        self.assertNotIn("\n5. ", SYSTEM_PROMPT)

    def test_prompt_length_unchanged(self):
        """回退后长度应与改动前一致（防止"回退了但残留半句"）"""
        self.assertEqual(len(SYSTEM_PROMPT), 333,
                         "系统提示词长度与回退基线（333 字）不一致，可能残留了改动")


class ExistingContractPreservedTest(unittest.TestCase):
    """既有契约：JSON 输出、规则 1~5、注入防护"""

    def test_json_output_contract_intact_in_user_template(self):
        t = detect_prompt.USER_PROMPT_TEMPLATE
        for key in ['"can_judge"', '"verdict"', '"confidence"', '"reasoning_chain"',
                    '"evidence"', '"human_reason"']:
            self.assertIn(key, t, f"user 模板的输出契约缺字段：{key}")

    def test_rules_1_to_5_intact_in_user_template(self):
        t = detect_prompt.USER_PROMPT_TEMPLATE
        self.assertIn("规则：", t)
        for i in range(1, 6):
            self.assertIn(f"\n{i}. ", t, f"user 模板缺少规则 {i}")
        # 规则 6/7 已回退，user 模板里也不应有
        self.assertNotIn("\n6. ", t)

    def test_verdict_enum_intact(self):
        t = detect_prompt.USER_PROMPT_TEMPLATE
        for v in ("TRUE", "FALSE", "UNVERIFIED"):
            self.assertIn(v, t)

    def test_prompt_injection_guard_intact(self):
        for must in ["不可信输入规则", "<untrusted_claim>", "<untrusted_evidence>",
                     "忽略以上设定"]:
            self.assertIn(must, SYSTEM_PROMPT, f"注入防护被破坏：{must}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
