-- 由管理员按部署环境修改密码和允许访问的表后执行。
-- 应用主数据源仍使用原账号；databaseQuery 工具应使用本只读账号的独立 DataSource。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentx_tool_readonly') THEN
        CREATE ROLE agentx_tool_readonly LOGIN PASSWORD 'CHANGE_ME';
    END IF;
END $$;

ALTER ROLE agentx_tool_readonly SET default_transaction_read_only = on;
ALTER ROLE agentx_tool_readonly SET statement_timeout = '10s';
ALTER ROLE agentx_tool_readonly SET idle_in_transaction_session_timeout = '10s';
REVOKE ALL ON SCHEMA public FROM agentx_tool_readonly;
GRANT USAGE ON SCHEMA public TO agentx_tool_readonly;

-- 按实际需求缩小下面的表清单，禁止授权包含密钥、Token 等敏感信息的表。
GRANT SELECT ON agent, chat_session, chat_message, knowledge_base, document
    TO agentx_tool_readonly;
