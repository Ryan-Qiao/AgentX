import React, { useState } from "react";
import { ApartmentOutlined, RobotOutlined } from "@ant-design/icons";
import { Button, Tabs, type TabsProps } from "antd";
import { useNavigate } from "react-router-dom";
import AgentTabContent from "./tabs/AgentTabContent.tsx";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import ChatTabContent from "./tabs/ChatTabContent.tsx";
import KnowledgeBaseTabContent from "./tabs/KnowledgeBaseTabContent.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import { useAgents } from "../hooks/useAgents.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";

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

  const handleTabChange = (key: string) => {
    setActiveKey(key);
    if (key === "agentTrace") navigate("/agent-trace");
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
        <div className="flex h-full flex-col items-center justify-center gap-3 bg-white px-6 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-indigo-50">
            <ApartmentOutlined className="text-xl text-indigo-500" />
          </div>
          <div className="text-sm font-medium text-zinc-800">Agent Trace</div>
          <div className="text-xs leading-5 text-zinc-500">通过 Trace ID 回放 Agent 的完整执行过程</div>
          <Button type="primary" onClick={() => navigate("/agent-trace")}>打开查询页面</Button>
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
  ];

  return (
    <div className="flex flex-col h-full">
      <div className="h-14 flex items-center border-b border-zinc-100 shrink-0">
        <div className="flex items-center gap-2.5 px-4">
          <div className="w-8 h-8 rounded-lg bg-indigo-50 flex items-center justify-center">
            <RobotOutlined className="text-base text-indigo-500" />
          </div>
          <div className="text-base font-semibold tracking-tight text-zinc-900 select-none">
            JChatMind
          </div>
        </div>
      </div>
      <div className="flex-1 min-h-0 flex flex-col pt-1">
        <Tabs
          activeKey={activeKey}
          onChange={handleTabChange}
          items={items}
          className="sidebar-tabs h-full flex flex-col [&_.ant-tabs-content-holder]:flex-1 [&_.ant-tabs-content-holder]:min-h-0 [&_.ant-tabs-content]:h-full [&_.ant-tabs-tabpane]:h-full"
        />
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
