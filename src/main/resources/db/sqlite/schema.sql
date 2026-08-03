-- ================================================================================
-- YKD Bot 数据库 Schema
-- 引擎: SQLite（jdbc:sqlite:./work/sqlite/conversation.db）
-- 规范: 所有时间字段用 TEXT 存储（SQLite 无 DATETIME 类型），
--       默认值 CURRENT_TIMESTAMP，Java 侧通过 MyBatis map-underscore-to-camel-case 映射
-- ================================================================================

-- ================================================================================
-- 1. conversation_message — 对话历史
-- 用途: 按微信用户隔离的滑动窗口记忆，最大 40 条
-- ================================================================================
CREATE TABLE IF NOT EXISTS conversation_message (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     TEXT    NOT NULL,             -- 微信用户 ID（from_user_id）
    role        TEXT    NOT NULL              -- 角色: user / assistant / system / tool
                       CHECK (role IN ('user', 'assistant', 'system', 'tool')),
    content     TEXT    NOT NULL,             -- 消息内容
    message_type TEXT  NOT NULL DEFAULT 'text', -- text / image / voice / video
    model_name  TEXT,                         -- 回复用的模型: DeepSeek / Agnes
    created_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_conversation_message_user_id_id
    ON conversation_message (user_id, id DESC);  -- 查用户最近 N 条消息


-- ================================================================================
-- 2. liepin_resume — 简历内容（纯文本）
-- 用途: 存储用户上传简历解析后的纯文本，供 DeepSeek AI 匹配打分
-- 关系: 1:1 → liepin_resume_asset（附件文件）
--      1:N → liepin_job_campaign（计划引用）
--      1:N → liepin_application_record（投递记录引用）
-- =====================================i===========================================
CREATE TABLE IF NOT EXISTS liepin_resume (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     TEXT    NOT NULL UNIQUE,       -- 每个用户仅一条，重新上传即覆盖
    file_name   TEXT,                          -- 原始文件名（如 简历.pdf）
    content     TEXT    NOT NULL,              -- 解析后的纯文本（PDFBox/POI 提取），截断至 10000 字
    updated_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- ================================================================================
-- 3. liepin_resume_asset — 简历附件（原始文件元信息）
-- 用途: 附件投递模式需要原始 PDF/Word 文件，文件以 SHA-256 命名存储在磁盘
-- 路径: <resume-directory>/<SHA256(userId)[:16]>/<SHA256(fileBytes)>.<ext>
-- 关系: 1:1 → liepin_resume（通过 user_id 关联）
-- ================================================================================
CREATE TABLE IF NOT EXISTS liepin_resume_asset (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     TEXT    NOT NULL UNIQUE,       -- 每个用户仅一条附件记录
    file_name   TEXT    NOT NULL,              -- 原始文件名
    file_path   TEXT    NOT NULL,              -- 磁盘绝对路径
    file_type   TEXT    NOT NULL,              -- pdf / doc / docx
    file_size   INTEGER NOT NULL,             -- 字节数
    file_hash   TEXT    NOT NULL,              -- SHA-256，去重 + 完整性校验
    created_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 唯一索引: 同用户同文件哈希不重复存储
CREATE UNIQUE INDEX IF NOT EXISTS idx_liepin_resume_asset_file_hash
    ON liepin_resume_asset (user_id, file_hash);


-- ================================================================================
-- 4. liepin_job_task — 搜索任务
-- 用途: 每次手动/自动职位搜索创建一条任务，状态机驱动异步流程
-- 流程: CREATED → SEARCHING → ANALYZING → WAITING_CONFIRMATION → SUBMITTING → SUCCEEDED
-- 关系: 1:N → liepin_job_posting（搜索结果）
--      1:N → liepin_application_record（投递记录）
-- ================================================================================
CREATE TABLE IF NOT EXISTS liepin_job_task (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id             TEXT    NOT NULL,       -- 发起搜索的微信用户
    keyword             TEXT    NOT NULL,       -- 搜索关键词（如 Java后端）
    city                TEXT    NOT NULL,       -- 目标城市（如 杭州）
    min_salary_k        INTEGER,               -- 最低月薪(K)，null 表示不限制
    max_salary_k        INTEGER,               -- 最高月薪(K)，null 表示不限制
    exclude_outsourcing INTEGER NOT NULL DEFAULT 1,  -- 是否排除外包(1=是)
    status              TEXT    NOT NULL,       -- CREATED/SEARCHING/ANALYZING/WAITING_CONFIRMATION/...
    message             TEXT,                   -- 状态描述文案（可直接展示给用户）
    created_at          TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_liepin_job_task_user_id_id
    ON liepin_job_task (user_id, id DESC);     -- 查用户最新任务


-- ================================================================================
-- 5. liepin_job_posting — 职位信息
-- 用途: 存储从猎聘搜索到的职位详情，包含 AI 匹配评分/理由/招呼语
-- 数据源: API 响应解析（优先） → DOM 降级抓取（兜底）
-- 关系: N:1 → liepin_job_task（task_id）
-- ================================================================================
CREATE TABLE IF NOT EXISTS liepin_job_posting (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id         INTEGER NOT NULL,           -- 属于哪次搜索
    external_job_id TEXT,                       -- 猎聘内部职位 ID
    job_name        TEXT    NOT NULL,           -- 职位名称
    company_name    TEXT,                       -- 公司名称
    company_industry TEXT,                      -- 行业
    company_scale   TEXT,                       -- 规模（如 500-999人）
    city            TEXT,                       -- 工作城市
    salary          TEXT,                       -- 薪资原文（如 15K-25K）
    education       TEXT,                       -- 学历要求
    experience      TEXT,                       -- 经验要求
    recruiter_name  TEXT,                       -- 招聘者姓名
    recruiter_title TEXT,                       -- 招聘者职位
    recruiter_im_id TEXT,                       -- 猎聘 IM 系统内部 ID
    published_at    TEXT,                       -- 发布时间
    description     TEXT,                       -- 拼接: 学历 + 经验 + 标签
    job_url         TEXT    NOT NULL,           -- 职位详情页 URL
    match_score     INTEGER,                   -- AI 匹配分数 0-100（DeepSeek 回填）
    match_reason    TEXT,                       -- AI 匹配理由
    greeting        TEXT,                       -- AI 生成的招呼语
    status          TEXT    NOT NULL DEFAULT 'CANDIDATE',  -- CANDIDATE/SUBMITTING/SUBMITTED/...
    created_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES liepin_job_task(id)
);
-- 查某次任务的所有职位，先按匹配分降序，分相同按 id 升序
CREATE INDEX IF NOT EXISTS idx_liepin_job_posting_task_id_score
    ON liepin_job_posting (task_id, match_score DESC, id ASC);


-- ================================================================================
-- 6. liepin_job_campaign — 自动投递计划
-- 用途: 定义自动投递的完整参数和调度节奏，由 Scheduler 每分钟扫描执行
-- 流程: CREATED → [用户启动] → RUNNING → PAUSED/STOPPED/LOGIN_REQUIRED/FAILED
-- 恢复: LOGIN_REQUIRED → [下次扫描检测到已登录] → RUNNING（自动）
-- 关系: N:1 → liepin_resume（resume_id）
--      1:N → liepin_application_record
-- ================================================================================
CREATE TABLE IF NOT EXISTS liepin_job_campaign (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id             TEXT    NOT NULL,       -- 计划归属
    name                TEXT    NOT NULL,       -- 计划名（如 "杭州-Java后端自动投递"）
    resume_id           INTEGER,               -- 使用的简历
    delivery_mode       TEXT    NOT NULL        -- 投递方式
                                CHECK (delivery_mode IN ('ONLINE', 'ATTACHMENT', 'AUTO')),
    keyword             TEXT    NOT NULL,       -- 搜索关键词
    city                TEXT    NOT NULL,       -- 目标城市
    min_salary_k        INTEGER,               -- 最低月薪(K)
    max_salary_k        INTEGER,               -- 最高月薪(K)
    min_match_score     INTEGER NOT NULL DEFAULT 85,  -- 最低 AI 匹分(0-100)，低于此分不投
    exclude_outsourcing INTEGER NOT NULL DEFAULT 1,   -- 排除外包(1=是)
    excluded_keywords   TEXT,                   -- 额外排除关键词，逗号分隔，命中不投
    daily_limit         INTEGER NOT NULL DEFAULT 3,   -- 每日投递上限
    interval_minutes    INTEGER NOT NULL DEFAULT 30,  -- 两轮执行间隔(分钟)
    status              TEXT    NOT NULL,       -- CREATED/RUNNING/PAUSED/STOPPED/LOGIN_REQUIRED/...
    consecutive_failures INTEGER NOT NULL DEFAULT 0, -- 连续失败次数，达阈值自动暂停
    message             TEXT,                   -- 状态描述
    last_run_at         TEXT,                   -- 上次执行时间
    next_run_at         TEXT,                   -- 下次执行时间，调度器据此判断到期
    created_at          TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (resume_id) REFERENCES liepin_resume(id)
);
-- 调度器核心索引: 查到期 RUNNING 计划（每分钟一次）
CREATE INDEX IF NOT EXISTS idx_liepin_job_campaign_due
    ON liepin_job_campaign (status, next_run_at);
-- 查用户最新计划
CREATE INDEX IF NOT EXISTS idx_liepin_job_campaign_user_id
    ON liepin_job_campaign (user_id, id DESC);


-- ================================================================================
-- 7. liepin_application_record — 投递记录
-- 用途: 记录每一次对特定职位的投递尝试，去重、追踪、限额统计
-- 去重: UNIQUE(user_id, external_job_key) + INSERT OR IGNORE
--       external_job_key = "id:<猎聘ID>" 或 "hash:<SHA256(URL+职位+公司)>"
-- 关系: N:1 → liepin_job_campaign、liepin_job_task、liepin_job_posting、liepin_resume
-- ================================================================================
CREATE TABLE IF NOT EXISTS liepin_application_record (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         TEXT    NOT NULL,           -- 投递人
    campaign_id     INTEGER,                   -- 所属计划（手动投递时为空）
    task_id         INTEGER,                   -- 所属搜索任务
    posting_id      INTEGER,                   -- 目标职位
    external_job_key TEXT   NOT NULL,           -- 去重键
    job_name        TEXT    NOT NULL,           -- 冗余: 投递时的职位名快照
    company_name    TEXT,                       -- 冗余: 投递时的公司名快照
    resume_id       INTEGER,                   -- 使用的简历
    delivery_mode   TEXT    NOT NULL,           -- ONLINE / ATTACHMENT
    status          TEXT    NOT NULL,           -- PENDING/CONTACTING/SENDING_RESUME/SUCCESS/FAILED/...
    attempt_count   INTEGER NOT NULL DEFAULT 0,-- 尝试次数，每次重试 +1
    failure_reason  TEXT,                       -- 失败原因
    contacted_at    TEXT,                       -- 点"聊一聊"的时间
    resume_sent_at  TEXT,                       -- 简历发送成功的时间
    created_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (campaign_id) REFERENCES liepin_job_campaign(id),
    FOREIGN KEY (task_id)     REFERENCES liepin_job_task(id),
    FOREIGN KEY (posting_id)  REFERENCES liepin_job_posting(id),
    FOREIGN KEY (resume_id)   REFERENCES liepin_resume(id),
    UNIQUE (user_id, external_job_key)          -- 核心去重: 同用户不重复投同职位
);
-- 今日成功统计 + 最近记录查询
CREATE INDEX IF NOT EXISTS idx_liepin_application_record_user_status
    ON liepin_application_record (user_id, status, id DESC);


-- ================================================================================
-- 8. reminder_task — 定时提醒
-- 用途: 存储四种类型的提醒任务，服务器重启后从 DB 恢复
-- ================================================================================
CREATE TABLE IF NOT EXISTS reminder_task (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id          TEXT    NOT NULL UNIQUE,    -- 8 位随机 ID，内存和 DB 的关联键
    user_id          TEXT    NOT NULL,           -- 提醒归属
    message          TEXT    NOT NULL,           -- 提醒内容
    time_expression  TEXT    NOT NULL,           -- 原始时间表达式（如 "10分钟后"）
    task_type        TEXT    NOT NULL            -- ONCE / DAILY / INTERVAL / WEEKLY
                             CHECK (task_type IN ('ONCE', 'DAILY', 'INTERVAL', 'WEEKLY')),
    interval_seconds INTEGER,                   -- INTERVAL 类型: 间隔秒数
    daily_time       TEXT,                       -- DAILY 类型: 目标时间（如 08:00）
    delay_seconds    INTEGER,                   -- ONCE 类型: 延时秒数
    cron_expression  TEXT,                       -- DAILY/WEEKLY 类型: Cron 表达式
    needs_processing INTEGER NOT NULL DEFAULT 0, -- 触发时是否需要调 LLM 处理(1=是)
    status           TEXT    NOT NULL DEFAULT 'ACTIVE'  -- ACTIVE / CANCELLED（软删除）
                             CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at       TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reminder_task_user
    ON reminder_task(user_id, status);          -- 恢复时查所有 ACTIVE 任务
CREATE TABLE IF NOT EXISTS knowledge_document (
                                                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                                                  user_id TEXT NOT NULL,
                                                  file_name TEXT NOT NULL,
                                                  file_type TEXT,
                                                  file_hash TEXT NOT NULL,
                                                  status TEXT NOT NULL,
                                                  created_at TEXT NOT NULL,
                                                  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS knowledge_chunk (
                                               id INTEGER PRIMARY KEY AUTOINCREMENT,
                                               document_id INTEGER NOT NULL,
                                               chunk_index INTEGER NOT NULL,
                                               content TEXT NOT NULL,
                                               embedding TEXT NOT NULL,
                                               created_at TEXT NOT NULL,
                                               FOREIGN KEY (document_id)
    REFERENCES knowledge_document(id)
    ON DELETE CASCADE
    );
CREATE INDEX IF NOT EXISTS idx_knowledge_document_user_id
    ON knowledge_document(user_id);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_document_id
    ON knowledge_chunk(document_id);