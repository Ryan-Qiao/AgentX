import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import {
  type ChatSessionVO,
  getChatSessions,
  deleteChatSession as deleteChatSessionApi,
  deleteChatSessions as deleteChatSessionsApi,
  updateChatSession as updateChatSessionApi,
  type UpdateChatSessionRequest,
} from "../api/api.ts";

interface ChatSessionsContextType {
  chatSessions: ChatSessionVO[];
  loading: boolean;
  refreshChatSessions: () => Promise<void>;
  deleteChatSession: (chatSessionId: string) => Promise<void>;
  deleteChatSessions: (chatSessionIds: string[]) => Promise<void>;
  updateChatSession: (
    chatSessionId: string,
    request: UpdateChatSessionRequest,
  ) => Promise<void>;
}

const ChatSessionsContext = createContext<ChatSessionsContextType | undefined>(
  undefined
);

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
    fetchChatSessions();
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

export function useChatSessionsContext() {
  const context = useContext(ChatSessionsContext);
  if (context === undefined) {
    throw new Error(
      "useChatSessionsContext must be used within a ChatSessionsProvider"
    );
  }
  return context;
}
