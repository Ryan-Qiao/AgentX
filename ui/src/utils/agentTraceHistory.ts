export interface AgentTraceHistoryItem {
  traceId: string;
  status: string;
  modelName?: string;
  totalSteps: number;
  durationMs?: number;
  queriedAt: string;
}

export const AGENT_TRACE_HISTORY_KEY = "agent-trace-query-history";
export const AGENT_TRACE_HISTORY_CHANGED = "agent-trace-history-changed";

export function readAgentTraceHistory(): AgentTraceHistoryItem[] {
  try {
    const value = JSON.parse(localStorage.getItem(AGENT_TRACE_HISTORY_KEY) || "[]");
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}

export function saveAgentTraceHistory(item: AgentTraceHistoryItem) {
  const history = readAgentTraceHistory().filter((entry) => entry.traceId !== item.traceId);
  localStorage.setItem(AGENT_TRACE_HISTORY_KEY, JSON.stringify([item, ...history].slice(0, 20)));
  window.dispatchEvent(new Event(AGENT_TRACE_HISTORY_CHANGED));
}

export function removeAgentTraceHistory(traceId: string) {
  localStorage.setItem(
    AGENT_TRACE_HISTORY_KEY,
    JSON.stringify(readAgentTraceHistory().filter((entry) => entry.traceId !== traceId)),
  );
  window.dispatchEvent(new Event(AGENT_TRACE_HISTORY_CHANGED));
}

export function clearAgentTraceHistory() {
  localStorage.removeItem(AGENT_TRACE_HISTORY_KEY);
  window.dispatchEvent(new Event(AGENT_TRACE_HISTORY_CHANGED));
}
