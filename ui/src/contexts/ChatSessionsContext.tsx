import React, { useEffect, useState, useCallback } from "react";
import {
  type ChatSessionVO,
  type UpdateChatSessionRequest,
  getChatSessions,
  deleteChatSession as deleteChatSessionApi,
  deleteChatSessions as deleteChatSessionsApi,
  updateChatSession as updateChatSessionApi,
} from "../api/api.ts";
import { ChatSessionsContext } from "./ChatSessionsContextDefinition.ts";

export function ChatSessionsProvider({ children }: { children: React.ReactNode }) {
  const [chatSessions, setChatSessions] = useState<ChatSessionVO[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchChatSessions = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await getChatSessions();
      setChatSessions(resp.chatSessions);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => void fetchChatSessions(), 0);
    return () => window.clearTimeout(timer);
  }, [fetchChatSessions]);

  const deleteChatSessionHandle = useCallback(async (chatSessionId: string) => {
    await deleteChatSessionApi(chatSessionId);
    await fetchChatSessions();
  }, [fetchChatSessions]);

  const deleteChatSessionsHandle = useCallback(async (chatSessionIds: string[]) => {
    await deleteChatSessionsApi(chatSessionIds);
    await fetchChatSessions();
  }, [fetchChatSessions]);

  const updateChatSessionHandle = useCallback(
    async (chatSessionId: string, request: UpdateChatSessionRequest) => {
      await updateChatSessionApi(chatSessionId, request);
      await fetchChatSessions();
    },
    [fetchChatSessions],
  );

  return (
    <ChatSessionsContext.Provider
      value={{
        chatSessions,
        loading,
        refreshChatSessions: fetchChatSessions,
        deleteChatSession: deleteChatSessionHandle,
        deleteChatSessions: deleteChatSessionsHandle,
        updateChatSession: updateChatSessionHandle,
      }}
    >
      {children}
    </ChatSessionsContext.Provider>
  );
}
