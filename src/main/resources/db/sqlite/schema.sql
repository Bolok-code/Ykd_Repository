CREATE TABLE IF NOT EXISTS conversation_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system', 'tool')),
    content TEXT NOT NULL,
    message_type TEXT NOT NULL DEFAULT 'text',
    model_name TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conversation_message_user_id_id
    ON conversation_message (user_id, id DESC);

CREATE TABLE IF NOT EXISTS liepin_resume (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL UNIQUE,
    file_name TEXT,
    content TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS liepin_job_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    keyword TEXT NOT NULL,
    city TEXT NOT NULL,
    min_salary_k INTEGER,
    max_salary_k INTEGER,
    exclude_outsourcing INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL,
    message TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_liepin_job_task_user_id_id
    ON liepin_job_task (user_id, id DESC);

CREATE TABLE IF NOT EXISTS liepin_job_posting (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL,
    external_job_id TEXT,
    job_name TEXT NOT NULL,
    company_name TEXT,
    company_industry TEXT,
    company_scale TEXT,
    city TEXT,
    salary TEXT,
    education TEXT,
    experience TEXT,
    recruiter_name TEXT,
    recruiter_title TEXT,
    recruiter_im_id TEXT,
    published_at TEXT,
    description TEXT,
    job_url TEXT NOT NULL,
    match_score INTEGER,
    match_reason TEXT,
    greeting TEXT,
    status TEXT NOT NULL DEFAULT 'CANDIDATE',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES liepin_job_task(id)
);

CREATE INDEX IF NOT EXISTS idx_liepin_job_posting_task_id_score
    ON liepin_job_posting (task_id, match_score DESC, id ASC);
CREATE TABLE IF NOT EXISTS liepin_resume_asset (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL UNIQUE,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_type TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    file_hash TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_liepin_resume_asset_file_hash
    ON liepin_resume_asset (user_id, file_hash);

CREATE TABLE IF NOT EXISTS liepin_job_campaign (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    resume_id INTEGER,
    delivery_mode TEXT NOT NULL CHECK (delivery_mode IN ('ONLINE', 'ATTACHMENT', 'AUTO')),
    keyword TEXT NOT NULL,
    city TEXT NOT NULL,
    min_salary_k INTEGER,
    max_salary_k INTEGER,
    min_match_score INTEGER NOT NULL DEFAULT 85,
    exclude_outsourcing INTEGER NOT NULL DEFAULT 1,
    excluded_keywords TEXT,
    daily_limit INTEGER NOT NULL DEFAULT 3,
    interval_minutes INTEGER NOT NULL DEFAULT 30,
    status TEXT NOT NULL,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    message TEXT,
    last_run_at TEXT,
    next_run_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (resume_id) REFERENCES liepin_resume(id)
);

CREATE INDEX IF NOT EXISTS idx_liepin_job_campaign_due
    ON liepin_job_campaign (status, next_run_at);

CREATE INDEX IF NOT EXISTS idx_liepin_job_campaign_user_id
    ON liepin_job_campaign (user_id, id DESC);

CREATE TABLE IF NOT EXISTS liepin_application_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    campaign_id INTEGER,
    task_id INTEGER,
    posting_id INTEGER,
    external_job_key TEXT NOT NULL,
    job_name TEXT NOT NULL,
    company_name TEXT,
    resume_id INTEGER,
    delivery_mode TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    failure_reason TEXT,
    contacted_at TEXT,
    resume_sent_at TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (campaign_id) REFERENCES liepin_job_campaign(id),
    FOREIGN KEY (task_id) REFERENCES liepin_job_task(id),
    FOREIGN KEY (posting_id) REFERENCES liepin_job_posting(id),
    FOREIGN KEY (resume_id) REFERENCES liepin_resume(id),
    UNIQUE (user_id, external_job_key)
);

CREATE INDEX IF NOT EXISTS idx_liepin_application_record_user_status
    ON liepin_application_record (user_id, status, id DESC);

CREATE TABLE IF NOT EXISTS reminder_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    message TEXT NOT NULL,
    time_expression TEXT NOT NULL,
    task_type TEXT NOT NULL CHECK (task_type IN ('ONCE', 'DAILY', 'INTERVAL', 'WEEKLY')),
    interval_seconds INTEGER,
    daily_time TEXT,
    delay_seconds INTEGER,
    cron_expression TEXT,
    needs_processing INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_reminder_task_user ON reminder_task(user_id, status);
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