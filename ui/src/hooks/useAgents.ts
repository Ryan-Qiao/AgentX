import { useCallback, useEffect, useState } from "react";
import {
  type AgentVO,
  createAgent,
  type CreateAgentRequest,
  getAgents,
  deleteAgent,
  updateAgent,
  type UpdateAgentRequest,
} from "../api/api.ts";

const AGENTS_CHANGED_EVENT = "jchatmind:agents-changed";

export function useAgents() {
  const [agents, setAgents] = useState<AgentVO[]>([]);

  const refreshAgents = useCallback(async () => {
    const resp = await getAgents();
    setAgents(resp.agents);
  }, []);

  useEffect(() => {
    const initialRefresh = window.setTimeout(() => void refreshAgents(), 0);

    const handleAgentsChanged = () => {
      refreshAgents().then();
    };

    window.addEventListener(AGENTS_CHANGED_EVENT, handleAgentsChanged);
    return () => {
      window.clearTimeout(initialRefresh);
      window.removeEventListener(AGENTS_CHANGED_EVENT, handleAgentsChanged);
    };
  }, [refreshAgents]);

  function notifyAgentsChanged() {
    window.dispatchEvent(new Event(AGENTS_CHANGED_EVENT));
  }

  async function createAgentHandle(agent: CreateAgentRequest) {
    await createAgent(agent);
    await refreshAgents();
    notifyAgentsChanged();
  }

  async function deleteAgentHandle(agentId: string) {
    await deleteAgent(agentId);
    await refreshAgents();
    notifyAgentsChanged();
  }

  async function updateAgentHandle(
    agentId: string,
    request: UpdateAgentRequest,
  ) {
    await updateAgent(agentId, request);
    await refreshAgents();
    notifyAgentsChanged();
  }

  return {
    agents,
    createAgentHandle,
    deleteAgentHandle,
    updateAgentHandle,
    refreshAgents,
  };
}
