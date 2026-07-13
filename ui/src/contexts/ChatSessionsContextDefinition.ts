import { createContext } from "react";
import type { ChatSessionVO, UpdateChatSessionRequest } from "../api/api.ts";

export interface ChatSessionsContextType {
  chatSessions: ChatSessionVO[];
  loading: boolean;
  refreshChatSessions: () => Promise<void>;
  deleteChatSession: (chatSessionId: string) => Promise<void>;
  deleteChatSessions: (chatSessionIds: string[]) => Promise<void>;
  updateChatSession: (chatSessionId: string, request: UpdateChatSessionRequest) => Promise<void>;
}

export const ChatSessionsContext = createContext<ChatSessionsContextType | undefined>(undefined);
