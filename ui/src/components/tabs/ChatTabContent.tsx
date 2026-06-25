import React, { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Popconfirm } from "antd";
import {
  PlusOutlined,
  MessageOutlined,
  DeleteOutlined,
} from "@ant-design/icons";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useAgents } from "../../hooks/useAgents.ts";

const ChatTabContent: React.FC = () => {
  const navigate = useNavigate();
  const { chatSessions, loading, deleteChatSession } = useChatSessions();
  const { agents } = useAgents();

  const agentMap = useMemo(() => {
    const map = new Map<string, string>();
    agents.forEach((agent) => {
      map.set(agent.id, agent.name);
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

  const getDisplayTitle = (session: { title?: string; agentId: string }) => {
    if (session.title) {
      return session.title;
    }
    const agentName = agentMap.get(session.agentId);
    return agentName ? `与 ${agentName} 的对话` : "新对话";
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
            {chatSessions.map((session) => (
              <div
                key={session.id}
                onClick={() => handleSelectChatSession(session.id)}
                className="w-full px-3 py-2.5 rounded-lg cursor-pointer transition-all hover:bg-zinc-50 active:bg-zinc-100 group relative"
              >
                <div className="flex items-start gap-2.5">
                  <div className="w-8 h-8 rounded-lg bg-indigo-50 flex items-center justify-center shrink-0 mt-0.5">
                    <MessageOutlined className="text-sm text-indigo-500" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-zinc-900 text-sm truncate">
                      {getDisplayTitle(session)}
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
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default ChatTabContent;
