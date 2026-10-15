"""
生成联调测试数据脚本（可重复执行 / 幂等）

前置条件：
    - 已执行 sql/01_fact_check_schema.sql 与 sql/02_fact_check_init.sql
    - MySQL 与 Redis 已启动，且本文件顶部的连接配置与实际一致

依赖（仅实际写入时需要；--dry-run 不需要任何第三方库）：
    fact-agent\.venv\Scripts\python.exe -m pip install -r fact-check/scripts/requirements.txt

用法：
    # 预览将要执行的 SQL（不连数据库、不需要装依赖）
    fact-agent\.venv\Scripts\python.exe fact-check/scripts/generate_test_data.py --dry-run

    # 实际写入
    fact-agent\.venv\Scripts\python.exe fact-check/scripts/generate_test_data.py

    # 不把记录同步到 Python Agent 的向量索引
    fact-agent\.venv\Scripts\python.exe fact-check/scripts/generate_test_data.py --skip-index

生成内容：
    用户（普通用户 + 复用 init.sql 的管理员）、权威信息、检测记录（含 1 条图片记录）、
    证据、站内信、收藏/评分/申诉、检测请求日志、操作日志、结论修订、内容安全审核日志、
    Redis 热度榜，并通过 Agent 的 /index/add 把有结论的记录写入向量索引
    （否则"库里已有记录却永远命不中语义去重"）。
"""
import argparse
import hashlib
import sys
from datetime import datetime, timedelta

try:
    import bcrypt
except ImportError:
    bcrypt = None
try:
    import pymysql
except ImportError:
    pymysql = None
try:
    import redis
except ImportError:
    redis = None
try:
    import requests
except ImportError:
    requests = None

# ---------- 配置 ----------
DB_CONFIG = {
    "host": "127.0.0.1",
    "port": 3306,
    "user": "root",
    "password": "1234",
    "database": "fact_check",
    "charset": "utf8mb4",
}
REDIS_HOST = "127.0.0.1"
REDIS_PORT = 6379
REDIS_PASSWORD = None  # 本地 Redis 未设密码；如设置了请修改

HOT_KEY = "fact:hot:claim"
DEFAULT_AGENT_URL = "http://127.0.0.1:8001"

# 由 --dry-run 设置为 True：不连数据库、不写 Redis、不调 Agent
DRY_RUN = False


# ======================================================================
# dry-run 用的假连接：只记录 SQL，不做任何真实 IO
# ======================================================================

class _DryRunCursor:
    def __init__(self):
        self.statements = []
        self._next_id = 1

    def execute(self, sql, params=None):
        self.statements.append((" ".join(sql.split()), params))
        self._next_id += 1

    def fetchone(self):
        # 一律返回 None：模拟"数据都不存在"，从而把所有 INSERT 都生成出来
        return None

    def fetchall(self):
        return []

    @property
    def lastrowid(self):
        return self._next_id

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class _DryRunConnection:
    def __init__(self):
        self.cursor_obj = _DryRunCursor()

    def cursor(self, *args, **kwargs):
        return self.cursor_obj

    def commit(self):
        pass

    def rollback(self):
        pass

    def close(self):
        pass


# ======================================================================
# 工具
# ======================================================================

def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def hash_pwd(raw: str) -> str:
    if bcrypt is None:
        # dry-run 下允许没装 bcrypt：给一个明显的占位密文
        return "$2b$12$" + "DRYRUN_PLACEHOLDER_HASH".ljust(53, "x")[:53]
    return bcrypt.hashpw(raw.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def log(msg: str):
    print(msg)


# ======================================================================
# 各表的幂等写入
# ======================================================================

def ensure_user(cursor, username, password, nickname, role):
    cursor.execute("SELECT id FROM t_user WHERE username = %s", (username,))
    row = cursor.fetchone()
    if row:
        log(f"  [存在] 用户 {username} id={row['id']}")
        return row["id"]
    cursor.execute(
        """INSERT INTO t_user(username, password, nickname, avatar, role, status, create_time, update_time)
           VALUES(%s, %s, %s, NULL, %s, 1, NOW(), NOW())""",
        (username, hash_pwd(password), nickname, role),
    )
    log(f"  [新增] 用户 {username} id={cursor.lastrowid}")
    return cursor.lastrowid


def ensure_authority(cursor, title, content, category, source, source_url, status=1):
    cursor.execute("SELECT id FROM t_authority_info WHERE title = %s", (title,))
    row = cursor.fetchone()
    if row:
        log(f"  [存在] 权威信息《{title}》 id={row['id']}")
        return row["id"]
    cursor.execute(
        """INSERT INTO t_authority_info(title, content, category, source, source_url, cover_image,
                                       status, publish_time, create_by, update_by, create_time, update_time)
           VALUES(%s, %s, %s, %s, %s, NULL, %s, NOW(), NULL, NULL, NOW(), NOW())""",
        (title, content, category, source, source_url, status),
    )
    log(f"  [新增] 权威信息《{title}》 id={cursor.lastrowid}")
    return cursor.lastrowid


def ensure_record(cursor, user_id, content, category, status, verdict, verdict_source,
                  confidence, reasoning_chain, ai_summary, hit_count,
                  source_type=1, image_url=None):
    """写入一条检测记录（幂等：按 content_hash 判重）

    source_type：1 文字，2 图片（图片记录需同时给 image_url）
    status：0 检测中，1 待人工诊断，2 已完成，3 检测失败
    """
    content_hash = sha256(content)
    cursor.execute("SELECT id FROM t_fact_check_record WHERE content_hash = %s", (content_hash,))
    row = cursor.fetchone()
    if row:
        log(f"  [存在] 记录《{content}》 id={row['id']}")
        return row["id"]
    cursor.execute(
        """INSERT INTO t_fact_check_record(user_id, content, content_hash, category, source_type, image_url,
                                          status, verdict, verdict_source, confidence, reasoning_chain,
                                          ai_summary, version, hit_count, assignee_id, deadline,
                                          authority_id, create_time, update_time, complete_time)
           VALUES(%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 1, %s, NULL, %s, NULL, NOW(), NOW(), %s)""",
        (
            user_id, content, content_hash, category, source_type, image_url,
            status, verdict, verdict_source, confidence, reasoning_chain, ai_summary, hit_count,
            datetime.now() + timedelta(hours=48) if status == 1 else None,
            datetime.now() if status == 2 else None,
        ),
    )
    log(f"  [新增] 记录《{content}》 id={cursor.lastrowid} source_type={source_type}")
    return cursor.lastrowid


def ensure_evidence(cursor, record_id, title, url, snippet, source, credibility, relevance):
    cursor.execute("SELECT id FROM t_evidence WHERE record_id = %s AND url = %s", (record_id, url))
    row = cursor.fetchone()
    if row:
        return row["id"]
    cursor.execute(
        """INSERT INTO t_evidence(record_id, title, url, snippet, source, source_credibility,
                                  relevance_score, source_type, create_time)
           VALUES(%s, %s, %s, %s, %s, %s, %s, 1, NOW())""",
        (record_id, title, url, snippet, source, credibility, relevance),
    )
    return cursor.lastrowid


def ensure_message(cursor, user_id, type_, title, content, related_id):
    cursor.execute(
        "SELECT id FROM t_message WHERE user_id = %s AND type = %s AND related_id = %s",
        (user_id, type_, related_id),
    )
    if cursor.fetchone():
        return
    cursor.execute(
        """INSERT INTO t_message(user_id, type, title, content, related_id, is_read, create_time)
           VALUES(%s, %s, %s, %s, %s, 0, NOW())""",
        (user_id, type_, title, content, related_id),
    )
    log(f"  [新增] 站内信 type={type_} related_id={related_id}")


def ensure_favorite(cursor, user_id, record_id):
    cursor.execute("SELECT id FROM t_user_favorite WHERE user_id = %s AND record_id = %s", (user_id, record_id))
    if cursor.fetchone():
        return
    cursor.execute("INSERT INTO t_user_favorite(user_id, record_id, create_time) VALUES(%s, %s, NOW())",
                   (user_id, record_id))
    log(f"  [新增] 收藏 recordId={record_id}")


def ensure_rating(cursor, user_id, record_id, rating=1):
    cursor.execute("SELECT id FROM t_record_rating WHERE user_id = %s AND record_id = %s", (user_id, record_id))
    if cursor.fetchone():
        return
    cursor.execute(
        """INSERT INTO t_record_rating(user_id, record_id, rating, create_time, update_time)
           VALUES(%s, %s, %s, NOW(), NOW())""",
        (user_id, record_id, rating),
    )
    log(f"  [新增] 评分 recordId={record_id} rating={rating}")


def ensure_appeal(cursor, record_id, user_id, reason_type, content, status=0,
                  handle_result=None, handled_by=None):
    cursor.execute(
        "SELECT id FROM t_appeal WHERE user_id = %s AND record_id = %s AND reason_type = %s",
        (user_id, record_id, reason_type),
    )
    row = cursor.fetchone()
    if row:
        log(f"  [存在] 申诉 recordId={record_id} id={row['id']}")
        return row["id"]
    cursor.execute(
        """INSERT INTO t_appeal(record_id, user_id, reason_type, content, status,
                                handle_result, handled_by, handle_time, create_time)
           VALUES(%s, %s, %s, %s, %s, %s, %s, %s, NOW())""",
        (record_id, user_id, reason_type, content, status, handle_result, handled_by,
         datetime.now() if status != 0 else None),
    )
    log(f"  [新增] 申诉 recordId={record_id} status={status} id={cursor.lastrowid}")
    return cursor.lastrowid


def ensure_conclusion_revision(cursor, record_id, old_verdict, new_verdict,
                               old_chain, new_chain, reason, source, appeal_id, admin_id):
    """结论修订留痕（管理员改结论 / 申诉采纳）

    幂等：按 (record_id, new_verdict, reason) 判重
    """
    cursor.execute(
        "SELECT id FROM t_conclusion_revision WHERE record_id = %s AND new_verdict = %s AND reason = %s",
        (record_id, new_verdict, reason),
    )
    if cursor.fetchone():
        return
    cursor.execute(
        """INSERT INTO t_conclusion_revision(record_id, old_verdict, new_verdict, old_reasoning_chain,
                                             new_reasoning_chain, reason, source, appeal_id, admin_id, create_time)
           VALUES(%s, %s, %s, %s, %s, %s, %s, %s, %s, NOW())""",
        (record_id, old_verdict, new_verdict, old_chain, new_chain, reason, source, appeal_id, admin_id),
    )
    log(f"  [新增] 结论修订 recordId={record_id} {old_verdict} -> {new_verdict}（source={source}）")


def ensure_operation_log(cursor, admin_id, operation, method, params, ip="127.0.0.1"):
    """管理员操作日志（幂等：按 admin_id + operation + method 判重）"""
    cursor.execute(
        "SELECT id FROM t_operation_log WHERE admin_id = %s AND operation = %s AND method = %s",
        (admin_id, operation, method),
    )
    if cursor.fetchone():
        return
    cursor.execute(
        """INSERT INTO t_operation_log(admin_id, operation, method, params, ip, create_time)
           VALUES(%s, %s, %s, %s, %s, NOW())""",
        (admin_id, operation, method, params, ip),
    )
    log(f"  [新增] 操作日志 {operation}")


def ensure_detect_log(cursor, user_id, content, category, source_type,
                      dedup_hit, hit_record_id, record_id, ip="127.0.0.1", user_agent="seed-script"):
    """检测请求日志（幂等：按 user_id + content_hash + dedup_hit 判重）"""
    content_hash = sha256(content)
    cursor.execute(
        "SELECT id FROM t_detect_log WHERE user_id = %s AND content_hash = %s AND dedup_hit = %s",
        (user_id, content_hash, dedup_hit),
    )
    if cursor.fetchone():
        return
    cursor.execute(
        """INSERT INTO t_detect_log(user_id, content, content_hash, category, source_type,
                                    dedup_hit, hit_record_id, record_id, ip, user_agent, create_time)
           VALUES(%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW())""",
        (user_id, content, content_hash, category, source_type,
         dedup_hit, hit_record_id, record_id, ip, user_agent),
    )
    log(f"  [新增] 检测请求日志 dedup_hit={dedup_hit} record_id={record_id}")


def ensure_content_safety_log(cursor, user_id, content, source_type, check_type, result, reason=None):
    """内容安全审核日志（幂等：按 user_id + content_hash + result 判重）

    内容安全审核**已实现**（`ContentSafetyServiceImpl` +
    `LocalRuleContentSafetyChecker`：命中违法交易/招揽类规则拒绝提交，
    命中敏感话题仅记录日志；provider 可配，配错会在启动时 ERROR 告警）。

    这里写入的是**演示数据**，用于让审核日志页面/统计有数据可看 ——
    不是"因为功能没做才造数据"。
    """
    content_hash = sha256(content) if content else None
    cursor.execute(
        "SELECT id FROM t_content_safety_log WHERE user_id = %s AND content_hash = %s AND result = %s",
        (user_id, content_hash, result),
    )
    if cursor.fetchone():
        return
    cursor.execute(
        """INSERT INTO t_content_safety_log(user_id, content_hash, source_type, check_type, result, reason, create_time)
           VALUES(%s, %s, %s, %s, %s, %s, NOW())""",
        (user_id, content_hash, source_type, check_type, result, reason),
    )
    log(f"  [新增] 内容安全审核日志 check_type={check_type} result={result}")


# ======================================================================
# Redis 与向量索引
# ======================================================================

def set_hot_scores(scores):
    if DRY_RUN:
        log(f"  [dry-run] 跳过 Redis 热度写入：{scores}")
        return
    if redis is None:
        log("  [跳过] 未安装 redis 库，跳过热度写入")
        return
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, password=REDIS_PASSWORD,
                    decode_responses=True, protocol=2)
    for record_id, score in scores:
        r.zadd(HOT_KEY, {str(record_id): score})
        log(f"  [Redis] 热度 {HOT_KEY} recordId={record_id} score={score}")


def sync_vector_index(records, agent_url):
    """把有结论的记录写入 Python Agent 的向量索引

    否则会出现"库里已有记录、语义去重却永远命不中"——因为 FAISS 索引是独立持久化的。
    """
    if DRY_RUN:
        log(f"  [dry-run] 跳过向量索引同步（{len(records)} 条）")
        return
    if requests is None:
        log("  [跳过] 未安装 requests 库，无法同步向量索引")
        return
    ok = fail = 0
    first_error = None
    for record_id, text in records:
        try:
            resp = requests.post(f"{agent_url}/index/add",
                                 json={"record_id": record_id, "text": text}, timeout=15)
            resp.raise_for_status()
            ok += 1
        except Exception as exc:
            fail += 1
            if first_error is None:
                first_error = exc
    log(f"  索引同步：成功 {ok} 条，失败 {fail} 条")
    if fail:
        log(f"  [提示] 首条失败原因：{first_error}")
        log("  [提示] 若 Agent 未启动，可先启动它再执行本脚本，或用 --skip-index 跳过")


# ======================================================================
# 主流程
# ======================================================================

def main():
    global DRY_RUN

    parser = argparse.ArgumentParser(description="生成 FactCheck 联调测试数据")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印将要执行的 SQL，不连数据库、不写 Redis、不调 Agent")
    parser.add_argument("--skip-index", action="store_true",
                        help="跳过把记录同步到 Agent 向量索引的步骤")
    parser.add_argument("--agent-url", default=DEFAULT_AGENT_URL,
                        help=f"Python Agent 地址（默认 {DEFAULT_AGENT_URL}）")
    args = parser.parse_args()
    DRY_RUN = args.dry_run

    if DRY_RUN:
        log("=== DRY-RUN：只打印 SQL，不做任何真实写入 ===\n")
        conn = _DryRunConnection()
    else:
        if pymysql is None:
            log("缺少 pymysql，请先安装依赖：fact-agent\.venv\Scripts\python.exe -m pip install -r fact-check/scripts/requirements.txt")
            sys.exit(1)
        conn = pymysql.connect(**DB_CONFIG)

    try:
        with conn.cursor(pymysql.cursors.DictCursor if pymysql else None) as cursor:
            log("=== 1. 用户 ===")
            uid = ensure_user(cursor, "demo_user", "123456", "演示用户", 0)
            # 管理员由 sql/02_fact_check_init.sql 创建（admin / 123456），此处保持一致，
            # 避免出现两个密码不同的管理员账号；已存在时 ensure_user 会直接复用
            admin_id = ensure_user(cursor, "admin", "123456", "系统管理员", 1)

            log("\n=== 2. 权威信息 ===")
            ensure_authority(cursor, "央行宣布降低存款准备金率",
                             "经核实，该消息属实，央行已发布正式公告。",
                             "财经", "中国人民银行", "https://www.pbc.gov.cn")
            ensure_authority(cursor, "喝醋不能预防感冒",
                             "专家辟谣：喝醋不能杀灭感冒病毒，不能替代药物治疗。",
                             "健康", "国家卫健委", "https://www.nhc.gov.cn")
            ensure_authority(cursor, "每天喝八杯水有益健康",
                             "适量饮水有益健康，但具体饮水量因人而异。",
                             "健康", "健康中国", "https://www.nhc.gov.cn")

            log("\n=== 3. 检测记录（文字 + 图片）===")
            r1 = ensure_record(cursor, uid, "喝醋可以预防感冒", "健康", 2, 2, 1, 0.86,
                               "1. 医学专家指出感冒由病毒引起；2. 醋无法杀灭呼吸道病毒。",
                               None, 3)
            r2 = ensure_record(cursor, uid, "央行宣布降低存款准备金率", "财经", 2, 1, 2, 0.95,
                               "与央行官网公告一致，消息属实。", None, 5)
            r3 = ensure_record(cursor, uid, "某地出现不明飞行物", "社会", 1, None, None, None,
                               None, "搜索结果多为社交媒体传闻，缺乏权威信源，无法判断。", 4)
            r4 = ensure_record(cursor, uid, "每天喝八杯水有益健康", "健康", 2, 1, 1, 0.88,
                               "适量饮水有益健康，但“八杯水”并非强制标准。", None, 2)
            # 图片检测记录：source_type=2 且带 image_url，用于验证图片链路的展示
            r5 = ensure_record(cursor, uid, "某地暴雨导致地铁进水（图片消息）", "社会", 2, 2, 1, 0.79,
                               "图片中的积水场景与本地当日天气记录不符，判定为旧图新传。", None, 1,
                               source_type=2, image_url="https://example.com/fact-check/demo/flood.jpg")

            log("\n=== 4. 证据 ===")
            ensure_evidence(cursor, r1, "喝醋能预防感冒吗？", "https://www.nhc.gov.cn/xcs/s3574/202001/xxx.shtml",
                            "官方辟谣：喝醋不能预防感冒。", "国家卫健委", 0.95, 0.93)
            ensure_evidence(cursor, r1, "感冒的预防与治疗", "https://www.who.int/news-room/fact-sheets/detail/influenza-(seasonal)",
                            "流感由病毒引起，醋酸无杀病毒作用。", "世界卫生组织", 0.95, 0.90)
            ensure_evidence(cursor, r2, "央行决定下调金融机构存款准备金率", "https://www.pbc.gov.cn/zhengcehuobisi/125207/125213/index.html",
                            "央行发布公告，宣布下调存款准备金率。", "中国人民银行", 0.95, 0.95)
            ensure_evidence(cursor, r5, "当地气象部门当日天气实况", "https://www.cma.gov.cn/demo/weather",
                            "当日无暴雨记录，网传图片与本地天气不符。", "中国气象局", 0.95, 0.88)

            log("\n=== 5. 站内信 ===")
            ensure_message(cursor, uid, 6, "欢迎使用事实检测平台", "欢迎使用，提交消息即可开始检测。", None)
            ensure_message(cursor, uid, 2, "消息已进入人工诊断队列",
                           "AI 暂时无法判断您的消息，已转交管理员人工诊断。", r3)

            log("\n=== 6. 收藏 / 评分 / 申诉 ===")
            ensure_favorite(cursor, uid, r1)
            ensure_rating(cursor, uid, r1, 1)
            appeal_id = ensure_appeal(cursor, r1, uid, 1, "我认为该结论证据不足，请复核", status=0)

            log("\n=== 7. 结论修订留痕 ===")
            # 1) 管理员主动修改（source=1）
            ensure_conclusion_revision(
                cursor, r4, 3, 1,
                "原结论为无法判定。",
                "适量饮水有益健康，但“八杯水”并非强制标准，说法本身不准确。",
                "补充权威来源后修改结论", 1, None, admin_id)
            # 2) 申诉采纳（source=2，关联申诉单）
            ensure_conclusion_revision(
                cursor, r1, 2, 2,
                "1. 医学专家指出感冒由病毒引起；2. 醋无法杀灭呼吸道病毒。",
                "维持原结论，并补充 WHO 与卫健委来源。",
                "申诉采纳复核：维持结论，补充证据", 2, appeal_id, admin_id)

            log("\n=== 8. 管理员操作日志 ===")
            ensure_operation_log(cursor, admin_id, "管理员登录", "UserController.login", '{"username":"admin"}')
            ensure_operation_log(cursor, admin_id, "修改结论", "AdminRecordController.updateConclusion",
                                 '{"recordId": %d, "reason": "补充权威来源后修改结论"}' % (r4 or 0))
            ensure_operation_log(cursor, admin_id, "发布权威信息", "AdminAuthorityController.save",
                                 '{"title": "喝醋不能预防感冒"}')

            log("\n=== 9. 检测请求日志 ===")
            # dedup_hit：0 未命中，1 精确命中，2 语义命中
            ensure_detect_log(cursor, uid, "喝醋可以预防感冒", "健康", 1, 0, None, r1)
            ensure_detect_log(cursor, uid, "喝醋可以预防感冒", "健康", 1, 1, r1, None)
            ensure_detect_log(cursor, uid, "醋能预防感冒吗", "健康", 1, 2, r1, None)
            ensure_detect_log(cursor, uid, "央行宣布降低存款准备金率", "财经", 1, 0, None, r2)
            ensure_detect_log(cursor, uid, "某地暴雨导致地铁进水", "社会", 2, 0, None, r5)

            log("\n=== 10. 内容安全审核日志（演示数据，S2 目前为空实现）===")
            ensure_content_safety_log(cursor, uid, "喝醋可以预防感冒", 1, "text", 0)
            ensure_content_safety_log(cursor, uid, "某地暴雨导致地铁进水", 2, "image", 0)
            ensure_content_safety_log(cursor, uid, "含违规词的内容示例", 1, "text", 1, "命中敏感词（演示数据）")

            log("\n=== 11. Redis 热度榜 ===")
            set_hot_scores([(r1, 10), (r2, 15), (r3, 12), (r4, 8), (r5, 6)])

            log("\n=== 12. 同步向量索引 ===")
            if args.skip_index:
                log("  [跳过] 指定了 --skip-index")
            else:
                # 只同步有结论（status=2）的记录
                sync_vector_index([(r1, "喝醋可以预防感冒"),
                                   (r2, "央行宣布降低存款准备金率"),
                                   (r4, "每天喝八杯水有益健康"),
                                   (r5, "某地暴雨导致地铁进水（图片消息）")], args.agent_url)

        if DRY_RUN:
            log("\n=== DRY-RUN 汇总：将要执行的 SQL ===")
            for i, (sql, params) in enumerate(conn.cursor_obj.statements, 1):
                log(f"{i:3d}. {sql}")
                if params:
                    log(f"     参数: {params}")
            log(f"\n共 {len(conn.cursor_obj.statements)} 条语句（未执行）")
        else:
            conn.commit()
            log("\nTest data generated OK")
            log("普通用户：demo_user / 123456")
            log("管理员：admin / 123456（由 sql/02_fact_check_init.sql 创建）")
    except Exception as e:
        conn.rollback()
        log(f"Generate failed: {e}")
        sys.exit(1)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
