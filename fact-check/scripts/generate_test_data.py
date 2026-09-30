"""
生成联调测试数据脚本（可重复执行）

前置条件：
    - 已执行 sql/fact_check_schema.sql 与 sql/fact_check_init.sql
    - MySQL 与 Redis 已启动，且本文件底部的连接配置与实际一致

依赖：
    py -3.10 -m pip install pymysql bcrypt redis

用法：
    py -3.10 fact-check/scripts/generate_test_data.py

生成内容：演示用户、权威信息、检测记录、证据、站内信、收藏/评分/申诉、Redis 热度
"""
import hashlib
import sys
from datetime import datetime, timedelta

import bcrypt
import pymysql
import redis

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


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def hash_pwd(raw: str) -> str:
    return bcrypt.hashpw(raw.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def ensure_user(cursor, username, password, nickname, role):
    cursor.execute("SELECT id FROM t_user WHERE username = %s", (username,))
    row = cursor.fetchone()
    if row:
        print(f"  [存在] 用户 {username} id={row['id']}")
        return row["id"]
    cursor.execute(
        """INSERT INTO t_user(username, password, nickname, avatar, role, status, create_time, update_time)
           VALUES(%s, %s, %s, NULL, %s, 1, NOW(), NOW())""",
        (username, hash_pwd(password), nickname, role),
    )
    print(f"  [新增] 用户 {username} id={cursor.lastrowid}")
    return cursor.lastrowid


def ensure_authority(cursor, title, content, category, source, source_url, status=1):
    cursor.execute("SELECT id FROM t_authority_info WHERE title = %s", (title,))
    row = cursor.fetchone()
    if row:
        print(f"  [存在] 权威信息《{title}》 id={row['id']}")
        return row["id"]
    cursor.execute(
        """INSERT INTO t_authority_info(title, content, category, source, source_url, cover_image,
                                       status, publish_time, create_by, update_by, create_time, update_time)
           VALUES(%s, %s, %s, %s, %s, NULL, %s, NOW(), NULL, NULL, NOW(), NOW())""",
        (title, content, category, source, source_url, status),
    )
    print(f"  [新增] 权威信息《{title}》 id={cursor.lastrowid}")
    return cursor.lastrowid


def ensure_record(cursor, user_id, content, category, status, verdict, verdict_source,
                  confidence, reasoning_chain, ai_summary, hit_count):
    content_hash = sha256(content)
    cursor.execute("SELECT id FROM t_fact_check_record WHERE content_hash = %s", (content_hash,))
    row = cursor.fetchone()
    if row:
        print(f"  [存在] 记录《{content}》 id={row['id']}")
        return row["id"]
    cursor.execute(
        """INSERT INTO t_fact_check_record(user_id, content, content_hash, category, source_type, image_url,
                                          status, verdict, verdict_source, confidence, reasoning_chain,
                                          ai_summary, version, hit_count, assignee_id, deadline,
                                          authority_id, create_time, update_time, complete_time)
           VALUES(%s, %s, %s, %s, 1, NULL, %s, %s, %s, %s, %s, %s, 1, %s, NULL, %s, NULL, NOW(), NOW(), %s)""",
        (
            user_id, content, content_hash, category, status, verdict, verdict_source,
            confidence, reasoning_chain, ai_summary, hit_count,
            datetime.now() + timedelta(hours=48) if status == 1 else None,
            datetime.now() if status == 2 else None,
        ),
    )
    print(f"  [新增] 记录《{content}》 id={cursor.lastrowid}")
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


def set_hot(record_id, score):
    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, password=REDIS_PASSWORD,
                    decode_responses=True, protocol=2)
    r.zadd(HOT_KEY, {str(record_id): score})
    print(f"  [Redis] 热度 {HOT_KEY} 添加 recordId={record_id}, score={score}")


def main():
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cursor:
            print("=== 1. 用户 ===")
            uid = ensure_user(cursor, "demo_user", "demo123", "演示用户", 0)
            # 管理员由 sql/fact_check_init.sql 创建（admin / Admin@123456），此处保持一致，
            # 避免出现两个密码不同的管理员账号；已存在时 ensure_user 会直接复用
            ensure_user(cursor, "admin", "Admin@123456", "系统管理员", 1)

            print("=== 2. 权威信息 ===")
            auth1 = ensure_authority(cursor, "央行宣布降低存款准备金率",
                                      "经核实，该消息属实，央行已发布正式公告。",
                                      "财经", "中国人民银行", "https://www.pbc.gov.cn")
            auth2 = ensure_authority(cursor, "喝醋不能预防感冒",
                                      "专家辟谣：喝醋不能杀灭感冒病毒，不能替代药物治疗。",
                                      "健康", "国家卫健委", "https://www.nhc.gov.cn")
            ensure_authority(cursor, "每天喝八杯水有益健康",
                             "适量饮水有益健康，但具体饮水量因人而异。",
                             "健康", "健康中国", "https://www.nhc.gov.cn")

            print("=== 3. 检测记录 ===")
            r1 = ensure_record(cursor, uid, "喝醋可以预防感冒", "健康", 2, 2, 1, 0.86,
                               "1. 医学专家指出感冒由病毒引起；2. 醋无法杀灭呼吸道病毒。",
                               None, 3)
            r2 = ensure_record(cursor, uid, "央行宣布降低存款准备金率", "财经", 2, 1, 2, 0.95,
                               "与央行官网公告一致，消息属实。", None, 5)
            r3 = ensure_record(cursor, uid, "某地出现不明飞行物", "社会", 1, None, None, None,
                               None, "搜索结果多为社交媒体传闻，缺乏权威信源，无法判断。", 4)
            r4 = ensure_record(cursor, uid, "每天喝八杯水有益健康", "健康", 2, 1, 1, 0.88,
                               "适量饮水有益健康，但“八杯水”并非强制标准。", None, 2)

            print("=== 4. 证据 ===")
            ensure_evidence(cursor, r1, "喝醋能预防感冒吗？", "https://www.nhc.gov.cn/xcs/s3574/202001/xxx.shtml",
                            "官方辟谣：喝醋不能预防感冒。", "国家卫健委", 0.95, 0.93)
            ensure_evidence(cursor, r1, "感冒的预防与治疗", "https://www.who.int/news-room/fact-sheets/detail/influenza-(seasonal)",
                            "流感由病毒引起，醋酸无杀病毒作用。", "世界卫生组织", 0.95, 0.90)
            ensure_evidence(cursor, r2, "央行决定下调金融机构存款准备金率", "https://www.pbc.gov.cn/zhengcehuobisi/125207/125213/index.html",
                            "央行发布公告，宣布下调存款准备金率。", "中国人民银行", 0.95, 0.95)

            print("=== 5. 站内信 ===")
            ensure_message(cursor, uid, 6, "欢迎使用事实检测平台", "欢迎使用，提交消息即可开始检测。", None)

            print("=== 6. 收藏 / 评分 / 申诉 ===")
            cursor.execute("SELECT id FROM t_user_favorite WHERE user_id = %s AND record_id = %s", (uid, r1))
            if not cursor.fetchone():
                cursor.execute("INSERT INTO t_user_favorite(user_id, record_id, create_time) VALUES(%s, %s, NOW())", (uid, r1))
                print(f"  [新增] 收藏 recordId={r1}")
            cursor.execute("SELECT id FROM t_record_rating WHERE user_id = %s AND record_id = %s", (uid, r1))
            if not cursor.fetchone():
                cursor.execute("INSERT INTO t_record_rating(user_id, record_id, rating, create_time, update_time) VALUES(%s, %s, 1, NOW(), NOW())", (uid, r1))
                print(f"  [新增] 评分 recordId={r1}")
            cursor.execute("SELECT id FROM t_appeal WHERE user_id = %s AND record_id = %s AND status = 0", (uid, r1))
            if not cursor.fetchone():
                cursor.execute("INSERT INTO t_appeal(record_id, user_id, reason_type, content, status, create_time) VALUES(%s, %s, 1, %s, 0, NOW())", (r1, uid, "我认为该结论证据不足，请复核"))
                print(f"  [新增] 申诉 recordId={r1}")

            print("=== 7. Redis 热度 ===")
            set_hot(r1, 10)
            set_hot(r2, 15)
            set_hot(r3, 12)
            set_hot(r4, 8)

        conn.commit()
        print("Test data generated OK")
        print("普通用户：demo_user / demo123")
        print("管理员：admin / Admin@123456（由 sql/fact_check_init.sql 创建）")
    except Exception as e:
        conn.rollback()
        print("Generate failed:", e)
        sys.exit(1)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
