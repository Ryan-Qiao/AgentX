import React, { useState, useMemo } from "react";
import { Select } from "antd";
import { DownOutlined } from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useNavigate } from "react-router-dom";
import {
  type AgentVO,
  createChatMessage,
  createChatSession,
} from "../../../api/api.ts";
import { getAgentEmoji } from "../../../utils";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";

interface DefaultAgentChatViewProps {
  handleSendMessage: (message: string) => void;
  loading: boolean;
  agents: AgentVO[];
}

const SUGGESTIONS = [
  "帮我检查一下这段代码的性能问题",
  "解释一下什么是 RAG 技术",
  "帮我写一篇博客提纲",
  "分析这份文档的重点内容",
];

const EmptyAgentChatView: React.FC<DefaultAgentChatViewProps> = ({
  loading,
  agents,
}) => {
  const [message, setMessage] = useState("");
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);

  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();

  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      emoji: getAgentEmoji(agent.id),
    }));
  }, [agents]);

  const effectiveAgentId = useMemo(() => {
    if (selectedAgentId) return selectedAgentId;
    return agents.length > 0 ? agents[0].id : null;
  }, [selectedAgentId, agents]);

  const currentAgent = useMemo(() => {
    return agentsWithEmoji.find((a) => a.id === effectiveAgentId);
  }, [effectiveAgentId, agentsWithEmoji]);

  return (
    <div className="flex flex-col h-full">
      {/* Agent 选择器 */}
      {agents.length > 0 && (
        <div className="border-b border-zinc-100 bg-white px-4 py-2.5">
          <Select
            value={effectiveAgentId}
            onChange={(value) => setSelectedAgentId(value)}
            style={{ width: 240 }}
            variant="borderless"
            suffixIcon={<DownOutlined className="text-zinc-400 text-xs" />}
            placeholder="选择智能体助手"
            optionRender={(option) => (
              <div className="flex items-center gap-2">
                <span className="text-base">
                  {agentsWithEmoji.find((a) => a.id === option.value)?.emoji}
                </span>
                <span className="text-sm">{option.label}</span>
              </div>
            )}
            options={agentsWithEmoji.map((agent) => ({
              value: agent.id,
              label: agent.name,
            }))}
          />
        </div>
      )}

      {/* 欢迎区域 */}
      <div className="flex-1 flex flex-col items-center justify-center px-6">
        <div className="max-w-xl w-full text-center">
          {currentAgent ? (
            <>
              <div className="w-14 h-14 rounded-2xl bg-indigo-50 flex items-center justify-center mx-auto mb-4 text-2xl">
                {currentAgent.emoji}
              </div>
              <h1 className="text-xl font-semibold text-zinc-900 mb-1.5">
                {currentAgent.name}
              </h1>
              {currentAgent.description && (
                <p className="text-sm text-zinc-500 mb-6 max-w-md mx-auto leading-relaxed">
                  {currentAgent.description}
                </p>
              )}
            </>
          ) : (
            <>
              <div className="w-14 h-14 rounded-2xl bg-zinc-100 flex items-center justify-center mx-auto mb-4">
                <span className="text-2xl">💬</span>
              </div>
              <h1 className="text-xl font-semibold text-zinc-900 mb-1.5">
                开始新的对话
              </h1>
              <p className="text-sm text-zinc-500 mb-6 max-w-md mx-auto">
                选择一个智能体助手开始聊天，或直接发送消息创建新会话
              </p>
            </>
          )}

          {/* 快捷建议 */}
          <div className="flex flex-wrap justify-center gap-2">
            {SUGGESTIONS.map((suggestion) => (
              <button
                key={suggestion}
                onClick={() => setMessage(suggestion)}
                className="px-3.5 py-1.5 text-sm text-zinc-500 bg-zinc-50 hover:bg-zinc-100 hover:text-zinc-700 rounded-full border border-zinc-200 transition-colors cursor-pointer"
              >
                {suggestion}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* 输入框 */}
      <div className="border-t border-zinc-100 bg-white px-4 pb-4 pt-3">
        <Sender
          onSubmit={async () => {
            if (!effectiveAgentId || !message.trim()) return;
            const response = await createChatSession({
              agentId: effectiveAgentId,
              title: message.slice(0, 20),
            });
            await createChatMessage({
              sessionId: response.chatSessionId ?? "",
              content: message,
              role: "user",
              agentId: effectiveAgentId,
            });
            await refreshChatSessions();
            setMessage("");
            navigate(`/chat/${response.chatSessionId}`);
          }}
          value={message}
          loading={loading}
          placeholder="输入消息开始对话..."
          onChange={(value) => {
            setMessage(value);
          }}
        />
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
