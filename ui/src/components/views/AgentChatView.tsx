import React, { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { message as antdMessage } from "antd";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createChatMessage,
  createChatSession,
  getChatMessagesBySessionId,
  getChatSession,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const { state } = useLocation();
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);

  // 流式输出状态（后端响应完成后一次性下发，前端做“假流式”逐字打印）
  const [streamingContent, setStreamingContent] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);

  // 逐字打印动画状态
  const charBufferRef = useRef<string[]>([]);
  const rafIdRef = useRef<number | null>(null);
  const lastFrameTimeRef = useRef<number>(0);
  const pendingFinalMessageRef = useRef<ChatMessageVO | null>(null);

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => [...prevMessages, message]);
  };

  // 启动逐字打印动画消费器
  const startTypewriter = useCallback(() => {
    if (rafIdRef.current !== null) return;

    const tick = (now: number) => {
      const buffer = charBufferRef.current;

      if (buffer.length === 0) {
        rafIdRef.current = null;
        // 打印完成：切换为正式消息
        const pending = pendingFinalMessageRef.current;
        if (pending) {
          pendingFinalMessageRef.current = null;
          addMessage(pending);
          setStreamingContent("");
          setIsStreaming(false);
        }
        return;
      }

      // 节奏控制：每帧间隔 ~16ms（60fps），每帧消费 charsPerFrame 个字符
      if (now - lastFrameTimeRef.current >= 16) {
        const remaining = buffer.length;
        // 自适应速率：内容越长越快，避免过于漫长
        let charsPerFrame: number;
        if (remaining > 800) {
          charsPerFrame = 6; // ~360 字/秒
        } else if (remaining > 300) {
          charsPerFrame = 4; // ~240 字/秒
        } else if (remaining > 100) {
          charsPerFrame = 2; // ~120 字/秒
        } else {
          charsPerFrame = 1; // ~60 字/秒，丝滑逐字
        }

        const chunk = buffer.splice(0, charsPerFrame).join("");
        if (chunk) {
          setStreamingContent((prev) => prev + chunk);
        }
        lastFrameTimeRef.current = now;
      }

      rafIdRef.current = requestAnimationFrame(tick);
    };

    rafIdRef.current = requestAnimationFrame(tick);
  }, []);

  // 重置打印动画
  const resetTypewriter = useCallback(() => {
    if (rafIdRef.current !== null) {
      cancelAnimationFrame(rafIdRef.current);
      rafIdRef.current = null;
    }
    charBufferRef.current = [];
    pendingFinalMessageRef.current = null;
    lastFrameTimeRef.current = 0;
  }, []);

  const [agentId, setAgentId] = useState<string>("");

  const getChatMessages = useCallback(async () => {
    if (!chatSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(chatSessionId);
    setMessages(resp.chatMessages);

    const fetchData = async () => {
      const resp = await getChatSession(chatSessionId);
      setAgentId(resp.chatSession.agentId);
    };
    fetchData().then();
  }, [chatSessionId]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages().then();
  }, [chatSessionId, getChatMessages]);

  const handleSendMessage = async (value: string | { text: string }) => {
    const message = typeof value === "string" ? value : value.text;

    console.log(message);

    if (!message || !message.trim()) return;

    if (!chatSessionId) {
      if (!agentId) {
        antdMessage.warning("请先创建一个智能体助手");
        return;
      }
      setLoading(true);
      try {
        const response = await createChatSession({
          agentId: agentId,
          title: message.slice(0, 20),
        });
        await refreshChatSessions();
        navigate(`/chat/${response.chatSessionId}`, {
          replace: true,
          state: {
            init: false,
            initMessage: message,
          },
        });
      } catch (error) {
        console.error("创建聊天会话失败:", error);
        antdMessage.error("创建聊天会话失败，请重试");
      } finally {
        setLoading(false);
      }
    } else {
      if (state?.init) {
        console.log("init", state.initMessage);
        await createChatMessage({
          agentId: agentId ?? "",
          sessionId: chatSessionId,
          role: "user",
          content: state.initMessage ?? "",
        });
      } else {
        console.log("ask", message);
        await createChatMessage({
          agentId: agentId ?? "",
          sessionId: chatSessionId,
          role: "user",
          content: message,
        });
      }
      await getChatMessages();
    }
  };

  const [displayAgentStatus, setDisplayAgentStatus] = useState<boolean>(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<
    SseMessageType | undefined
  >(undefined);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    const es = new EventSource(
      `http://localhost:8080/sse/connect/${chatSessionId}`,
    );
    es.onmessage = (event) => {
      console.log("Received message:", event.data);
    };
    es.onerror = (error) => {
      console.error("SSE error:", error);
    };

    es.addEventListener("message", (event) => {
      const message = JSON.parse(event.data) as SseMessage;

      if (message.type === "AI_STREAMING_CONTENT") {
        // 后端已不再推送流式增量，这里作为兑底处理：直接追加显示
        const delta = message.payload.message?.content || "";
        if (delta) {
          setStreamingContent((prev) => prev + delta);
        }
        setIsStreaming(true);
        setDisplayAgentStatus(false);
      } else if (message.type === "AI_GENERATED_CONTENT") {
        // 完整消息到达：启动“假流式”逐字打印动画
        const finalMsg = message.payload.message;
        const content = finalMsg?.content || "";

        // 先重置状态、隐藏 agent 状态、启用打印区域
        resetTypewriter();
        setStreamingContent("");
        setIsStreaming(true);
        setDisplayAgentStatus(false);

        if (!content) {
          // 空内容直接提交
          addMessage(finalMsg);
          setIsStreaming(false);
          return;
        }

        // 入队 + 记录待提交消息 + 启动动画
        charBufferRef.current = Array.from(content);
        pendingFinalMessageRef.current = finalMsg;
        startTypewriter();
      } else if (message.type === "AI_PLANNING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_PLANNING");
      } else if (message.type === "AI_THINKING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_THINKING");
      } else if (message.type === "AI_EXECUTING") {
        setDisplayAgentStatus(true);
        setAgentStatusText(message.payload.statusText);
        setAgentStatusType("AI_EXECUTING");
      } else if (message.type === "AI_DONE") {
        setDisplayAgentStatus(false);
        setAgentStatusText("");
        setAgentStatusType(undefined);
      } else {
        throw new Error(`Unknown message type: ${message.type}`);
      }
    });

    es.addEventListener("init", (event) => {
      console.log("Received init message:", event.data);
    });

    return () => {
      console.log("Closing SSE connection.");
      es.close();
      resetTypewriter();
    };
  }, [chatSessionId, startTypewriter, resetTypewriter]);

  if (!chatSessionId) {
    return (
      <EmptyAgentChatView
        agents={agents}
        loading={loading}
        handleSendMessage={handleSendMessage}
      />
    );
  }

  return (
    <div className="flex flex-col h-full">
      <AgentChatHistory
        messages={messages}
        streamingContent={streamingContent}
        isStreaming={isStreaming}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
      />
      <div className="border-t border-zinc-100 p-4 bg-white">
        <AgentChatInput onSend={handleSendMessage} />
      </div>
    </div>
  );
};

export default AgentChatView;
