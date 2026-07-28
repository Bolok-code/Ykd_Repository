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