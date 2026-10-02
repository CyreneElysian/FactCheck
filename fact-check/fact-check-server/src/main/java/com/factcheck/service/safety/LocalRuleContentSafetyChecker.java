package com.factcheck.service.safety;

import com.factcheck.dto.ContentSafetyResult;
import com.factcheck.properties.ContentSafetyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 本地规则内容安全审核（确定性、离线、零成本）
 *
 * <h3>为什么不是"敏感词命中就拒绝"</h3>
 * 这是一个**事实核查**平台，用户提交的内容天然会包含"病毒""疫情""诈骗""赌博""爆炸"这类词
 * ——因为它们正是待核查的说法本身。按敏感词直接拒绝会把产品最核心的场景全部挡掉
 * （例如"武汉又出现新型病毒了，大家不要出门"）。
 *
 * <h3>因此本实现把规则分成两级</h3>
 * <ul>
 *   <li><b>BLOCK（拒绝）</b>：四类明确的"违法交易 / 招揽广告"，且**每条规则都要求
 *       "违法词 + 联系方式"两个信号同时命中**（联系方式包括"加微信/私聊/扫码/点击链接"等话术，
 *       以及手机号、QQ 号、微信号、短链等正则模式）。
 *       只提到违法词、没有招揽特征的内容一律不拦——那更像是在讨论某个说法；</li>
 *   <li><b>REVIEW（放行但留痕）</b>：命中敏感话题词（疫情、恐怖袭击、自杀等），只写审核日志。</li>
 * </ul>
 *
 * <h3>能力边界（如实说明）</h3>
 * <ul>
 *   <li>这是"粗筛"，不是完整的内容审核：无法识别图片内容本身，也无法识别谐音、拆字、拼音等变体，
 *       词表必然滞后于新的黑话。真正的图片与变体识别需要云端审核服务
 *       （已通过 {@link ContentSafetyChecker} 预留接入点）。</li>
 *   <li>图片审核当前只校验地址（协议、主机名、可选域名白名单），并对地址文本跑一遍文本规则
 *       ——本地规则拿不到图片内容，这一点已在《技术方案》中注明。</li>
 *   <li>正则只作用于前 {@link #MAX_ANALYZE_LENGTH} 个字符，避免超长文本造成扫描开销；
 *       超出部分不参与匹配（已知取舍）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocalRuleContentSafetyChecker implements ContentSafetyChecker {

    public static final String NAME = "local";

    /**
     * 参与规则匹配的最大字符数
     */
    private static final int MAX_ANALYZE_LENGTH = 2000;

    // ------------------------------------------------------------------
    // 词表与模式
    // ------------------------------------------------------------------

    /**
     * 招揽特征：联系方式与交易意图话术。BLOCK 规则一律要求命中其中之一（或其正则模式）。
     */
    private static final Set<String> CONTACT_WORDS = words(
            "加微信", "加v", "加qq", "私聊", "私我", "联系我", "联系客服", "微信咨询", "扫码",
            "点击链接", "复制链接", "电话咨询", "详聊", "咨询我", "名额有限", "包过",
            "上门服务", "收费服务", "长期供应", "现货供应");

    /**
     * 招揽特征的正则：手机号、QQ 号、微信号、短链
     */
    private static final List<Pattern> CONTACT_PATTERNS = patterns(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)",                            // 手机号
            "(?i)qq\\s*[:：]?\\s*\\d{5,12}",                           // QQ 号
            "(?i)微信\\s*[:：号]?\\s*[A-Za-z][A-Za-z0-9_-]{5,19}",     // 微信号
            "(?i)(t\\.cn|dwz\\.cn|url\\.cn|bit\\.ly|suo\\.im)/");      // 短链

    /**
     * 色情招嫖类词汇
     */
    private static final Set<String> PORNO_WORDS = words(
            "色情服务", "裸聊", "约炮", "招嫖", "嫖娼", "一夜情", "福利姬", "楼凤",
            "成人视频", "色情直播", "特殊服务", "小姐上门", "援交");

    /**
     * 赌博类词汇
     */
    private static final Set<String> GAMBLING_WORDS = words(
            "赌博网站", "博彩平台", "博彩公司", "真人荷官", "六合彩", "时时彩", "百家乐",
            "赌球", "私彩", "棋牌外挂", "线上赌场", "澳门赌场");

    /**
     * 诈骗引流类话术
     */
    private static final Set<String> FRAUD_WORDS = words(
            "刷单返利", "兼职日结", "高额返利", "稳赚不赔", "内幕消息", "荐股", "带单",
            "代开发票", "办证", "代考", "贷款秒批", "无视征信", "洗白征信", "杀猪盘",
            "冒充客服", "解封账号", "花呗套现", "信用卡套现");

    /**
     * 违禁品词汇
     */
    private static final Set<String> ILLEGAL_GOODS_WORDS = words(
            "枪支", "弹药", "毒品", "冰毒", "摇头丸", "迷药", "假币", "假钞",
            "银行卡四件套", "身份证买卖", "假证");

    /**
     * 敏感话题词（命中只留痕、不拦截）
     */
    private static final Set<String> SENSITIVE_TOPIC_WORDS = words(
            "疫情", "病毒", "传染病", "恐怖袭击", "爆炸", "枪击", "骚乱", "暴动",
            "邪教", "自杀", "吸毒", "毒品", "游行", "罢工");

    /**
     * 拒绝规则：每条都要求"违法词 + 招揽特征"同时命中
     */
    private static final List<Rule> BLOCK_RULES = List.of(
            new Rule("色情招嫖", List.of(wordCondition(PORNO_WORDS), contactCondition())),
            new Rule("赌博推广", List.of(wordCondition(GAMBLING_WORDS), contactCondition())),
            new Rule("诈骗引流", List.of(wordCondition(FRAUD_WORDS), contactCondition())),
            new Rule("违禁品交易", List.of(wordCondition(ILLEGAL_GOODS_WORDS), contactCondition())));

    /**
     * 放行但留痕的规则
     */
    private static final List<Rule> REVIEW_RULES = List.of(
            new Rule("敏感话题", List.of(wordCondition(SENSITIVE_TOPIC_WORDS))));

    private final ContentSafetyProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ContentSafetyResult checkText(String text) {
        String content = analyze(text);
        if (content.isEmpty()) {
            return ContentSafetyResult.pass();
        }

        for (Rule rule : BLOCK_RULES) {
            if (rule.matches(content)) {
                return ContentSafetyResult.block(rule.category(), rule.describe(content));
            }
        }
        for (Rule rule : REVIEW_RULES) {
            if (rule.matches(content)) {
                return ContentSafetyResult.review(rule.category(), rule.describe(content));
            }
        }
        return ContentSafetyResult.pass();
    }

    @Override
    public ContentSafetyResult checkImage(String imageUrl) {
        String url = imageUrl == null ? "" : imageUrl.trim();
        if (url.isEmpty()) {
            return ContentSafetyResult.pass();
        }

        // 1) 地址必须合法：只允许 http/https，且必须有主机名
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return ContentSafetyResult.block("图片地址非法", "无法解析为 URI：" + abbreviate(url));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return ContentSafetyResult.block("图片地址非法", "协议不允许：" + scheme);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return ContentSafetyResult.block("图片地址非法", "缺少主机名：" + abbreviate(url));
        }

        // 2) 可选：域名白名单（为空表示不限制域名）
        List<String> whitelist = properties.getImageHostWhitelist();
        if (whitelist != null && !whitelist.isEmpty()) {
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = whitelist.stream()
                    .filter(item -> item != null && !item.trim().isEmpty())
                    .map(item -> item.trim().toLowerCase(Locale.ROOT))
                    .anyMatch(item -> host.equals(item) || host.endsWith("." + item));
            if (!allowed) {
                return ContentSafetyResult.block("图片地址不可信", "主机不在白名单：" + host);
            }
        }

        // 3) 很多广告会把话术塞进文件名或路径，因此对地址文本再跑一遍文本规则
        ContentSafetyResult textResult = checkText(url);
        if (!textResult.isPass()) {
            return textResult;
        }

        log.debug("本地规则只能校验图片地址、无法识别图片内容：{}", abbreviate(url));
        return ContentSafetyResult.pass();
    }

    /**
     * 截断 + 转小写，作为规则匹配的输入
     */
    private String analyze(String text) {
        String content = text == null ? "" : text.trim();
        if (content.length() > MAX_ANALYZE_LENGTH) {
            content = content.substring(0, MAX_ANALYZE_LENGTH);
        }
        return content.toLowerCase(Locale.ROOT);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }

    private static Set<String> words(String... items) {
        // 统一小写，保证与 analyze() 的输出可比
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(items).forEach(item -> set.add(item.toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableSet(set);
    }

    private static List<Pattern> patterns(String... regexes) {
        List<Pattern> list = new ArrayList<>(regexes.length);
        Arrays.stream(regexes).forEach(regex -> list.add(Pattern.compile(regex)));
        return Collections.unmodifiableList(list);
    }

    private static boolean hitWord(Set<String> words, String text) {
        return words.stream().anyMatch(text::contains);
    }

    private static boolean hitContactPattern(String text) {
        return CONTACT_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    private static Condition wordCondition(Set<String> words) {
        return new Condition(text -> hitWord(words, text), words);
    }
    /**
     * 招揽特征条件：词表命中 或 命中手机号/QQ/微信/短链等模式
     */
    private static Condition contactCondition() {
        return new Condition(
                text -> hitWord(CONTACT_WORDS, text) || hitContactPattern(text),
                CONTACT_WORDS);
    }

    /**
     * 一个匹配条件：命中任一即算满足（条件之间是"与"关系）
     *
     * <p>注意：本项目编译目标是 Java 11，不能用 record（Java 16+），因此写成普通静态内部类。</p>
     */
    private static final class Condition {

        private final Predicate<String> matcher;

        /**
         * 该条件对应的词表；模式类条件传 null（用于生成可读的命中详情）
         */
        private final Set<String> words;

        private Condition(Predicate<String> matcher, Set<String> words) {
            this.matcher = matcher;
            this.words = words;
        }

        boolean matches(String text) {
            return matcher.test(text);
        }

        Set<String> words() {
            return words;
        }
    }

    /**
     * 一条规则：所有条件都命中才算命中
     */
    private static final class Rule {

        private final String category;
        private final List<Condition> conditions;

        private Rule(String category, List<Condition> conditions) {
            this.category = category;
            this.conditions = conditions;
        }

        String category() {
            return category;
        }

        boolean matches(String text) {
            return conditions.stream().allMatch(condition -> condition.matches(text));
        }

        /**
         * 命中的词 / 模式，用于审核日志（便于事后核对与调整词表）
         */
        String describe(String text) {
            List<String> hits = new ArrayList<>();
            for (Condition condition : conditions) {
                String word = condition.words() == null ? null
                        : condition.words().stream().filter(text::contains).findFirst().orElse(null);
                if (word != null) {
                    hits.add(word);
                } else if (condition.matches(text)) {
                    hits.add("联系方式模式");
                }
            }
            return String.join(" + ", hits);
        }
    }
}
