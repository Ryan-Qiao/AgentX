CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE agent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    name TEXT NOT NULL,                    -- Agent 名称
    description TEXT,                      -- 描述（用户可见）
    system_prompt TEXT,                    -- 系统指令
    model TEXT,                            -- 默认使用的模型
    allowed_tools JSONB,                   -- 允许使用的工具列表
    allowed_kbs JSONB,                     -- 允许访问的知识库
    chat_options JSONB,                    -- 其它配置项（温度、top_p、最大token）
    auto_memory_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auto_memory_interval INT NOT NULL DEFAULT 10,
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE chat_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    agent_id UUID REFERENCES agent(id) ON DELETE SET NULL,  -- 绑定的 Agent
    
    title TEXT,                          -- 自动生成的标题
    metadata JSONB,                      -- 扩展（例如输入语言、设备类型）
  
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE chat_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,

    role TEXT NOT NULL,                      -- user / assistant / system / tool
    content TEXT,                            -- 主体内容
    metadata JSONB,                          -- 工具调用、RAG 片段、模型参数等
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE agent_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    source_message_id UUID REFERENCES chat_message(id) ON DELETE SET NULL,

    memory_scope TEXT NOT NULL DEFAULT 'core', -- core / retrieved
    memory_type TEXT NOT NULL DEFAULT 'fact', -- preference / fact / decision / issue / task / feedback
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(1024),
    priority INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    last_used_at TIMESTAMP
);

CREATE INDEX idx_agent_memory_agent_enabled_priority
ON agent_memory (agent_id, enabled, memory_scope, priority DESC, updated_at DESC);

CREATE INDEX idx_agent_memory_embedding
ON agent_memory
USING ivfflat (embedding vector_l2_ops)
WITH (lists = 100);

CREATE TABLE agent_memory_job_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    processed_user_message_count INT NOT NULL DEFAULT 0,
    last_processed_message_id UUID REFERENCES chat_message(id) ON DELETE SET NULL,
    last_processed_message_created_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (agent_id, session_id)
);

CREATE TABLE user_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID,
    source_message_id UUID REFERENCES chat_message(id) ON DELETE SET NULL,

    memory_type TEXT NOT NULL DEFAULT 'preference', -- preference / profile / communication / constraint
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    confidence NUMERIC(4, 3) NOT NULL DEFAULT 1.0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    last_used_at TIMESTAMP
);

CREATE INDEX idx_user_memory_global_enabled_priority
ON user_memory (user_id, enabled, priority DESC, updated_at DESC);

CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name TEXT NOT NULL,
    description TEXT,
    metadata JSONB,                         -- 业务属性，如行业/标签

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,

    filename TEXT NOT NULL,
    filetype TEXT,                          -- pdf / md / txt 等
    size BIGINT,                            -- 文件大小
    metadata JSONB,                         -- 页数、上传方式、解析参数等

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE chunk_bge_m3 (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,

    content TEXT NOT NULL,                  -- 切片后的文本内容
    metadata JSONB,                         -- 页码、段落号、chunk index 等

    embedding VECTOR(1024) NOT NULL,        -- bge_m3 模型是 1024 维的向量

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 给向量加索引
CREATE INDEX idx_chunk_embedding
ON chunk_bge_m3
USING ivfflat (embedding vector_l2_ops)
WITH (lists = 100);
