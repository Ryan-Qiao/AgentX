import React, { useMemo } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button, Popconfirm } from "antd";
import {
  PlusOutlined,
  MessageOutlined,
  DeleteOutlined,
} from "@ant-design/icons";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { getAgentEmoji } from "../../utils";

const ChatTabContent: React.FC = () => {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const { chatSessions, loading, deleteChatSession } = useChatSessions();
  const { agents } = useAgents();

  const agentMap = useMemo(() => {
    const map = new Map<string, { name: string; description?: string }>();
    agents.forEach((agent) => {
      map.set(agent.id, {
        name: agent.name,
        description: agent.description,
      });
    });
    return map;
  }, [agents]);

  const handleCreateNewChat = () => {
    navigate("/chat");
  };

  const handleSelectChatSession = (chatSessionId: string) => {
    navigate(`/chat/${chatSessionId}`);
  };

  const handleDeleteChatSession = async (chatSessionId: string) => {
    await deleteChatSession(chatSessionId);
  };

  const getDisplayTitle = (session: { title?: string; agentId?: string | null }) => {
    if (session.title) {
      return session.title;
    }
    if (!session.agentId) {
      return "历史对话";
    }
    const agent = agentMap.get(session.agentId);
    return agent ? `与 ${agent.name} 的对话` : "新对话";
  };

  const getAgentLabel = (agentId?: string | null) => {
    if (!agentId) {
      return "已删除的智能体";
    }
    return agentMap.get(agentId)?.name ?? "已删除的智能体";
  };

  return (
    <div className="flex flex-col h-full px-3 pt-2">
      <Button
        type="primary"
        icon={<PlusOutlined />}
        onClick={handleCreateNewChat}
        className="w-full"
      >
        新聊天
      </Button>
      <div className="flex-1 min-h-0 overflow-y-auto mt-3">
        {loading ? (
          <div className="flex flex-col items-center justify-center h-full text-zinc-400">
            <p className="text-sm">加载中...</p>
          </div>
        ) : chatSessions.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-zinc-400">
            <MessageOutlined className="text-3xl mb-2 text-zinc-300" />
            <p className="text-sm">暂无聊天记录</p>
            <p className="text-xs mt-1">点击上方按钮创建新聊天</p>
          </div>
        ) : (
          <div className="space-y-0.5">
            {chatSessions.map((session) => {
              const active = pathname === `/chat/${session.id}`;
              const agentLabel = getAgentLabel(session.agentId);
              return (
                <div
                  key={session.id}
                  onClick={() => handleSelectChatSession(session.id)}
                  className={`w-full px-3 py-2.5 rounded-xl cursor-pointer transition-all group relative border ${
                    active
                      ? "bg-indigo-50/80 border-indigo-100 shadow-sm"
                      : "border-transparent hover:bg-zinc-50 active:bg-zinc-100"
                  }`}
                >
                  <div className="flex items-start gap-2.5">
                    <div
                      className={`w-8 h-8 rounded-xl flex items-center justify-center shrink-0 mt-0.5 text-sm ring-1 ${
                        active
                          ? "bg-white ring-indigo-100"
                          : "bg-gradient-to-br from-zinc-50 to-indigo-50 ring-zinc-100"
                      }`}
                      title={agentLabel}
                    >
                      {getAgentEmoji(session.agentId)}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div
                        className={`font-medium text-sm truncate ${
                          active ? "text-indigo-950" : "text-zinc-900"
                        }`}
                        title={getDisplayTitle(session)}
                      >
                        {getDisplayTitle(session)}
                      </div>
                      <div className="mt-1 flex items-center gap-1.5 min-w-0">
                        <span
                          className={`max-w-full truncate rounded-full px-2 py-0.5 text-[11px] leading-4 ring-1 ${
                            active
                              ? "bg-white/80 text-indigo-700 ring-indigo-100"
                              : "bg-zinc-50 text-zinc-500 ring-zinc-100"
                          }`}
                          title={`使用 Agent：${agentLabel}`}
                        >
                          {agentLabel}
                        </span>
                      </div>
                    </div>
                    <div onClick={(e) => e.stopPropagation()} className="mt-0.5">
                      <Popconfirm
                        title="确定要删除这条聊天记录吗？"
                        description="删除后将无法恢复"
                        onConfirm={() => handleDeleteChatSession(session.id)}
                        okText="确定"
                        cancelText="取消"
                      >
                        <Button
                          type="text"
                          size="small"
                          icon={<DeleteOutlined />}
                          className="opacity-0 group-hover:opacity-100 transition-opacity shrink-0 text-zinc-400 hover:text-red-500"
                          danger
                        />
                      </Popconfirm>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export default ChatTabContent;
