import React, { useEffect, useState } from "react";
import { ApartmentOutlined, RobotOutlined, MessageOutlined, DatabaseOutlined, ClockCircleOutlined, DeleteOutlined, BulbOutlined } from "@ant-design/icons";
import { Empty, Tabs, Tag, type TabsProps } from "antd";
import { useNavigate } from "react-router-dom";
import AgentTabContent from "./tabs/AgentTabContent.tsx";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import ChatTabContent from "./tabs/ChatTabContent.tsx";
import KnowledgeBaseTabContent from "./tabs/KnowledgeBaseTabContent.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import UserMemoryTabContent from "./tabs/UserMemoryTabContent.tsx";
import { useAgents } from "../hooks/useAgents.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";
import {
  AGENT_TRACE_HISTORY_CHANGED,
  clearAgentTraceHistory,
  readAgentTraceHistory,
  removeAgentTraceHistory,
  type AgentTraceHistoryItem,
} from "../utils/agentTraceHistory.ts";

interface SideMenuProps {
  children?: React.ReactNode;
}

const SideMenu: React.FC<SideMenuProps> = () => {
  const navigate = useNavigate();

  const [isAddAgentModalOpen, setIsAddAgentModalOpen] = useState(false);
  const toggleAddAgentModal = () => {
    setIsAddAgentModalOpen(!isAddAgentModalOpen);
    setEditingAgent(null);
  };

  const [editingAgent, setEditingAgent] = useState<
    import("../api/api.ts").AgentVO | null
  >(null);

  const [isAddKnowledgeBaseModalOpen, setIsAddKnowledgeBaseModalOpen] =
    useState(false);
  const toggleAddKnowledgeBaseModal = () => {
    setIsAddKnowledgeBaseModalOpen(!isAddKnowledgeBaseModalOpen);
  };
  const { agents, createAgentHandle, deleteAgentHandle, updateAgentHandle } =
    useAgents();

  const [activeKey, setActiveKey] = useState(() => {
    if (location.pathname.startsWith("/agent-trace")) return "agentTrace";
    if (location.pathname.startsWith("/agent")) return "agent";
    if (location.pathname.startsWith("/knowledge-base")) return "knowledgeBase";
    if (location.pathname.startsWith("/chat")) return "chat";
    return "agent";
  });

  const { knowledgeBases, createKnowledgeBaseHandle } = useKnowledgeBases();
  const [traceHistory, setTraceHistory] = useState<AgentTraceHistoryItem[]>(() =>
    readAgentTraceHistory(),
  );

  useEffect(() => {
    const refresh = () => setTraceHistory(readAgentTraceHistory());
    window.addEventListener(AGENT_TRACE_HISTORY_CHANGED, refresh);
    window.addEventListener("storage", refresh);
    return () => {
      window.removeEventListener(AGENT_TRACE_HISTORY_CHANGED, refresh);
      window.removeEventListener("storage", refresh);
    };
  }, []);

  const handleTabChange = (key: string) => {
    setActiveKey(key);
    if (key === "agentTrace") navigate("/agent-trace");
    if (key === "agent") navigate("/agent");
    if (key === "chat") navigate("/chat");
    if (key === "knowledgeBase") navigate("/knowledge-base");
  };

  const items: TabsProps["items"] = [
    {
      key: "agent",
      label: <span className="select-none">智能体助手</span>,
      children: (
        <AgentTabContent
          agents={agents}
          onSelectAgent={() => {}}
          onCreateAgentClick={toggleAddAgentModal}
          onEditAgent={(agent) => {
            setEditingAgent(agent);
            setIsAddAgentModalOpen(true);
          }}
          onDeleteAgent={deleteAgentHandle}
        />
      ),
    },
    {
      key: "chat",
      label: <span className="select-none">聊天记录</span>,
      children: <ChatTabContent />,
    },
    {
      key: "agentTrace",
      label: <span className="select-none">Trace</span>,
      children: (
        <div className="flex h-full min-h-0 flex-col bg-white">
          <div className="flex items-center justify-between border-b border-zinc-100 px-4 py-3">
            <div>
              <div className="text-sm font-semibold text-zinc-900">最近查询</div>
              <div className="mt-0.5 text-xs text-zinc-400">最多保留 20 条</div>
            </div>
            {traceHistory.length > 0 && (
              <button
                type="button"
                className="text-xs text-zinc-400 transition-colors hover:text-red-500"
                onClick={clearAgentTraceHistory}
              >
                清空
              </button>
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-2">
            {traceHistory.length === 0 ? (
              <div className="flex h-full items-center justify-center px-4">
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无查询记录" />
              </div>
            ) : (
              <div className="space-y-1">
                {traceHistory.map((item) => (
                  <button
                    type="button"
                    key={item.traceId}
                    className="group/history w-full rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-zinc-50"
                    onClick={() => navigate(`/agent-trace?traceId=${encodeURIComponent(item.traceId)}`)}
                  >
                    <div className="flex items-start gap-2">
                      <ClockCircleOutlined className="mt-0.5 shrink-0 text-zinc-400" />
                      <div className="min-w-0 flex-1">
                        <div className="truncate font-mono text-xs text-zinc-700" title={item.traceId}>
                          {item.traceId.slice(0, 8)}…{item.traceId.slice(-6)}
                        </div>
                        <div className="mt-1 flex items-center gap-1.5 text-[11px] text-zinc-400">
                          <Tag
                            bordered={false}
                            color={item.status === "COMPLETED" ? "success" : item.status === "FAILED" ? "error" : "processing"}
                            className="!m-0 !px-1.5 !text-[10px]"
                          >
                            {item.status}
                          </Tag>
                          <span className="truncate">{item.modelName || "未知模型"}</span>
                          <span>· {item.totalSteps} 步</span>
                        </div>
                        <div className="mt-1 text-[11px] text-zinc-400">
                          {new Date(item.queriedAt).toLocaleString("zh-CN", { hour12: false })}
                        </div>
                      </div>
                      <span
                        role="button"
                        tabIndex={0}
                        aria-label="删除查询记录"
                        className="rounded p-1 text-zinc-300 opacity-0 transition-all hover:bg-white hover:text-red-500 group-hover/history:opacity-100"
                        onClick={(event) => {
                          event.stopPropagation();
                          removeAgentTraceHistory(item.traceId);
                        }}
                        onKeyDown={(event) => {
                          if (event.key === "Enter" || event.key === " ") {
                            event.stopPropagation();
                            removeAgentTraceHistory(item.traceId);
                          }
                        }}
                      >
                        <DeleteOutlined />
                      </span>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      ),
    },
    {
      key: "knowledgeBase",
      label: <span className="select-none">知识库</span>,
      children: (
        <KnowledgeBaseTabContent
          knowledgeBases={knowledgeBases}
          onCreateKnowledgeBaseClick={toggleAddKnowledgeBaseModal}
          onSelectKnowledgeBase={(knowledgeBaseId) => {
            navigate(`/knowledge-base/${knowledgeBaseId}`);
          }}
        />
      ),
    },
    {
      key: "memory",
      label: <span className="select-none">记忆</span>,
      children: <UserMemoryTabContent />,
    },
  ];

  return (
    <div className="flex h-full">
      <nav className="w-16 shrink-0 border-r border-[var(--border)] bg-[var(--background)] flex flex-col items-center py-3 gap-2">
        <div className="brand-mark mb-3" aria-label="AgentX">X</div>
        {[
          ["agent", <RobotOutlined />, "智能体"],
          ["chat", <MessageOutlined />, "聊天"],
          ["knowledgeBase", <DatabaseOutlined />, "知识库"],
          ["memory", <BulbOutlined />, "用户级记忆"],
          ["agentTrace", <ApartmentOutlined />, "Trace"],
        ].map(([key, icon, label]) => (
          <button key={key as string} title={label as string} onClick={() => handleTabChange(key as string)} className={`rail-button ${activeKey === key ? "is-active" : ""}`}>
            {icon as React.ReactNode}
          </button>
        ))}
      </nav>
      <div className="min-w-0 flex-1 flex flex-col">
        <div className="h-16 flex items-center px-5 shrink-0">
          <div><div className="text-[11px] uppercase tracking-[.16em] text-zinc-400">Workspace</div><div className="text-base font-semibold tracking-tight text-zinc-900">AgentX</div></div>
        </div>
        <div className="flex-1 min-h-0 flex flex-col">
        <Tabs
          activeKey={activeKey}
          onChange={handleTabChange}
          items={items}
          className="sidebar-tabs h-full flex flex-col [&_.ant-tabs-content-holder]:flex-1 [&_.ant-tabs-content-holder]:min-h-0 [&_.ant-tabs-content]:h-full [&_.ant-tabs-tabpane]:h-full"
        />
        </div>
      </div>
      <AddAgentModal
        open={isAddAgentModalOpen}
        onClose={toggleAddAgentModal}
        createAgentHandle={createAgentHandle}
        updateAgentHandle={updateAgentHandle}
        editingAgent={editingAgent}
      />
      <AddKnowledgeBaseModal
        open={isAddKnowledgeBaseModalOpen}
        onClose={toggleAddKnowledgeBaseModal}
        createKnowledgeBaseHandle={createKnowledgeBaseHandle}
      />
    </div>
  );
};

export default SideMenu;
