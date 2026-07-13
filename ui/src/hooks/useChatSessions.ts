import { useChatSessionsContext } from "../contexts/useChatSessionsContext.ts";

export function useChatSessions() {
  return useChatSessionsContext();
}
