import { Routes, Route } from "react-router-dom";
import { lazy, Suspense } from "react";
import Layout from "../layout/Layout.tsx";
import Sidebar from "../layout/Sidebar.tsx";
import SideMenu from "./SideMenu.tsx";
import Content from "../layout/Content.tsx";
const AgentChatView = lazy(() => import("./views/AgentChatView.tsx"));
const KnowledgeBaseView = lazy(() => import("./views/KnowledgeBaseView.tsx"));
const AgentTraceView = lazy(() => import("./views/AgentTraceView.tsx"));

export default function JChatMindLayout() {
  return (
    <Layout>
      <Sidebar>
        <SideMenu />
      </Sidebar>
      <Content>
        <Suspense fallback={<div className="p-6 text-zinc-500">正在加载…</div>}>
        <Routes>
          <Route path="/" element={<AgentChatView />} />
          <Route path="/agent" element={<AgentChatView />} />
          <Route path="/chat" element={<AgentChatView />} />
          <Route path="/chat/:chatSessionId" element={<AgentChatView />} />
          <Route path="/knowledge-base" element={<KnowledgeBaseView />} />
          <Route path="/agent-trace" element={<AgentTraceView />} />
          <Route
            path="/knowledge-base/:knowledgeBaseId"
            element={<KnowledgeBaseView />}
          />
        </Routes>
        </Suspense>
      </Content>
    </Layout>
  );
}
