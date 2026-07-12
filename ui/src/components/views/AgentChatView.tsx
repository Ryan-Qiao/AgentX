import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { Button, Modal, Input, Select, Collapse, message as antdMessage } from "antd";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createAgentMemory,
  createUserMemory,
  createChatMessage,
  createChatSession,
  getAgentMemories,
  getChatMessagesBySessionId,
  getChatSession,
  getUserMemories,
  type AgentMemoryVO,
  type UserMemoryVO,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";
import { getAgentEmoji } from "../../utils";

const MIN_AGENT_STATUS_VISIBLE_MS = 500;

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const { state } = useLocation();
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [agentMemories, setAgentMemories] = useState<AgentMemoryVO[]>([]);
  const [userMemories, setUserMemories] = useState<UserMemoryVO[]>([]);
  const [memoryTarget, setMemoryTarget] = useState<"agent" | "user">("agent");
  const [memoryModalOpen, setMemoryModalOpen] = useState(false);
  const [memoryDraft, setMemoryDraft] = useState({
    title: "",
    content: "",
    memoryType: "fact",
  });

  // 流式输出状态（后端响应完成后一次性下发，前端做“假流式”逐字打印）
  const [streamingContent, setStreamingContent] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [displayAgentStatus, setDisplayAgentStatus] = useState<boolean>(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<
    SseMessageType | undefined
  >(undefined);

  // 逐字打印动画状态
  const charBufferRef = useRef<string[]>([]);
  const typewriterTimerRef = useRef<number | null>(null);
  const pendingFinalMessageRef = useRef<ChatMessageVO | null>(null);
  const initMessageSentRef = useRef<string | null>(null);
  const activeChatSessionIdRef = useRef<string | undefined>(chatSessionId);
  const currentRunHasAssistantContentRef = useRef(false);
  const agentStatusShownAtRef = useRef<number | null>(null);
  const typewriterStartTimeoutRef = useRef<number | null>(null);

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => {
      if (prevMessages.some((item) => item.id === message.id)) {
        return prevMessages;
      }
      return [...prevMessages, message];
    });
  };

  const replaceMessage = (messageId: string, message: ChatMessageVO) => {
    setMessages((prevMessages) =>
      prevMessages.map((item) => (item.id === messageId ? message : item)),
    );
  };

  const removeMessage = (messageId: string) => {
    setMessages((prevMessages) =>
      prevMessages.filter((item) => item.id !== messageId),
    );
  };

  // 启动逐字打印动画消费器
  const startTypewriter = useCallback(() => {
    if (typewriterTimerRef.current !== null) return;

    const tick = () => {
      const buffer = charBufferRef.current;

      if (buffer.length === 0) {
        typewriterTimerRef.current = null;
        // 打印完成：切换为正式消息
        const pending = pendingFinalMessageRef.current;
        if (pending) {
          pendingFinalMessageRef.current = null;
          currentRunHasAssistantContentRef.current = false;
          addMessage(pending);
          setStreamingContent("");
          setIsStreaming(false);
        }
        return;
      }

      const remaining = buffer.length;
      const interval = remaining > 300 ? 12 : remaining > 120 ? 20 : remaining > 40 ? 25 : 40;
      const char = buffer.shift();
      if (char) {
        setStreamingContent((prev) => prev + char);
      }
      typewriterTimerRef.current = window.setTimeout(tick, interval);
    };

    typewriterTimerRef.current = window.setTimeout(tick, 100);
  }, []);

  // 重置打印动画
  const resetTypewriter = useCallback(() => {
    if (typewriterStartTimeoutRef.current !== null) {
      window.clearTimeout(typewriterStartTimeoutRef.current);
      typewriterStartTimeoutRef.current = null;
    }
    if (typewriterTimerRef.current !== null) {
      window.clearTimeout(typewriterTimerRef.current);
      typewriterTimerRef.current = null;
    }
    charBufferRef.current = [];
    pendingFinalMessageRef.current = null;
  }, []);

  const showAgentStatus = useCallback((type: SseMessageType, text: string) => {
    setIsStreaming(false);
    setStreamingContent("");
    setDisplayAgentStatus(true);
    setAgentStatusText(text);
    setAgentStatusType(type);
    agentStatusShownAtRef.current = Date.now();
  }, []);

  const resetAssistantRuntimeState = useCallback(() => {
    resetTypewriter();
    currentRunHasAssistantContentRef.current = false;
    agentStatusShownAtRef.current = null;
    setStreamingContent("");
    setIsStreaming(false);
    setDisplayAgentStatus(false);
    setAgentStatusText("");
    setAgentStatusType(undefined);
  }, [resetTypewriter]);

  const showAssistantWaiting = useCallback(() => {
    resetAssistantRuntimeState();
    showAgentStatus("AI_THINKING", "正在理解你的问题");
  }, [resetAssistantRuntimeState, showAgentStatus]);

  const startTypewriterAfterStatusDelay = useCallback(() => {
    const shownAt = agentStatusShownAtRef.current;
    const elapsed = shownAt == null ? MIN_AGENT_STATUS_VISIBLE_MS : Date.now() - shownAt;
    const delay = Math.max(0, MIN_AGENT_STATUS_VISIBLE_MS - elapsed);

    const start = () => {
      typewriterStartTimeoutRef.current = null;
      if (!pendingFinalMessageRef.current) {
        return;
      }
      setDisplayAgentStatus(false);
      setAgentStatusText("");
      setAgentStatusType(undefined);
      setStreamingContent("");
      setIsStreaming(true);
      startTypewriter();
    };

    if (delay > 0) {
      setIsStreaming(false);
      setStreamingContent("");
      typewriterStartTimeoutRef.current = window.setTimeout(start, delay);
      return;
    }

    start();
  }, [startTypewriter]);

  const [agentId, setAgentId] = useState<string>("");

  const currentAgent = useMemo(() => {
    if (!agentId) return null;
    return agents.find((agent) => agent.id === agentId) ?? null;
  }, [agentId, agents]);

  const refreshAgentMemories = useCallback(async () => {
    if (!agentId) {
      setAgentMemories([]);
      return;
    }
    const resp = await getAgentMemories(agentId);
    setAgentMemories(resp.agentMemories.filter((memory) => memory.enabled));
  }, [agentId]);

  const refreshUserMemories = useCallback(async () => {
    const resp = await getUserMemories();
    setUserMemories(resp.userMemories.filter((memory) => memory.enabled));
  }, []);

  const getChatMessages = useCallback(async (targetSessionId = chatSessionId) => {
    if (!targetSessionId) {
      return;
    }
    const resp = await getChatMessagesBySessionId(targetSessionId);
    const isSendingInitMessage = Boolean(state?.init && state.initMessage);
    if (
      activeChatSessionIdRef.current === targetSessionId &&
      !(isSendingInitMessage && resp.chatMessages.length === 0)
    ) {
      const isTypewriterActive =
        typewriterStartTimeoutRef.current !== null ||
        typewriterTimerRef.current !== null ||
        charBufferRef.current.length > 0 ||
        pendingFinalMessageRef.current !== null;
      const pendingAssistantMessageId = pendingFinalMessageRef.current?.id;
      const chatMessages = isTypewriterActive && pendingAssistantMessageId
        ? resp.chatMessages.filter((message) => message.id !== pendingAssistantMessageId)
        : resp.chatMessages;

      setMessages(chatMessages);

      const latestMessage = chatMessages[chatMessages.length - 1];
      if (latestMessage?.role === "assistant" && !isTypewriterActive) {
        resetAssistantRuntimeState();
      }
    }

    const fetchData = async () => {
      const resp = await getChatSession(targetSessionId);
      if (activeChatSessionIdRef.current === targetSessionId) {
        setAgentId(resp.chatSession.agentId ?? "");
      }
    };
    fetchData().then();
  }, [chatSessionId, resetAssistantRuntimeState, state]);

  // Router 会复用当前组件。会话切换时必须在浏览器绘制前清掉旧会话状态，
  // 否则新会话历史加载完成前会短暂显示上一段对话。
  useLayoutEffect(() => {
    activeChatSessionIdRef.current = chatSessionId;
    setMessages([]);
    setAgentId("");
    setAgentMemories([]);
    resetAssistantRuntimeState();
  }, [chatSessionId, resetAssistantRuntimeState]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    getChatMessages(chatSessionId).then();
  }, [chatSessionId, getChatMessages]);

  useEffect(() => {
    refreshAgentMemories().catch((error) => {
      console.error("获取 Agent 记忆失败:", error);
    });
  }, [refreshAgentMemories]);

  useEffect(() => {
    refreshUserMemories().catch((error) => {
      console.error("获取 User Memory 失败:", error);
    });
  }, [refreshUserMemories]);

  const openRememberModal = (target: "agent" | "user") => {
    const lastUserMessage = [...messages].reverse().find((item) => item.role === "user");
    setMemoryTarget(target);
    setMemoryDraft({
      title: "",
      content: lastUserMessage?.content ?? "",
      memoryType: target === "user" ? "preference" : "fact",
    });
    setMemoryModalOpen(true);
  };

  const saveMemory = async () => {
    if (memoryTarget === "agent" && !agentId) {
      antdMessage.warning("当前对话没有关联 Agent");
      return;
    }
    if (!memoryDraft.title.trim() || !memoryDraft.content.trim()) {
      antdMessage.warning("请填写记忆标题和内容");
      return;
    }

    if (memoryTarget === "user") {
      await createUserMemory({
        title: memoryDraft.title.trim(),
        content: memoryDraft.content.trim(),
        memoryType: memoryDraft.memoryType,
        enabled: true,
        priority: 0,
        confidence: 1,
      });
      await refreshUserMemories();
      antdMessage.success("所有 Agent 已可参考该用户记忆");
    } else {
      await createAgentMemory(agentId, {
        title: memoryDraft.title.trim(),
        content: memoryDraft.content.trim(),
        memoryType: memoryDraft.memoryType,
        enabled: true,
        priority: 0,
      });
      await refreshAgentMemories();
      antdMessage.success("当前 Agent 已记住该内容");
    }

    setMemoryModalOpen(false);
    setMemoryDraft({ title: "", content: "", memoryType: "fact" });
  };

  const sendMessageToSession = useCallback(
    async (content: string, targetAgentId: string, targetSessionId: string) => {
      const trimmedContent = content.trim();
      if (!trimmedContent) return;

      const optimisticId = `optimistic-user-${Date.now()}`;
      addMessage({
        id: optimisticId,
        sessionId: targetSessionId,
        role: "user",
        content: trimmedContent,
      });
      showAssistantWaiting();

      try {
        const response = await createChatMessage({
          agentId: targetAgentId,
          sessionId: targetSessionId,
          role: "user",
          content: trimmedContent,
        });
        replaceMessage(optimisticId, {
          id: response.chatMessageId,
          sessionId: targetSessionId,
          role: "user",
          content: trimmedContent,
          metadata: { traceId: response.traceId },
        });
        await refreshChatSessions();
      } catch (error) {
        console.error("发送聊天消息失败:", error);
        removeMessage(optimisticId);
        resetAssistantRuntimeState();
        antdMessage.error("消息发送失败，请重试");
      }
    },
    [refreshChatSessions, resetAssistantRuntimeState, showAssistantWaiting],
  );

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
            init: true,
            initMessage: message,
            initAgentId: agentId,
          },
        });
      } catch (error) {
        console.error("创建聊天会话失败:", error);
        antdMessage.error("创建聊天会话失败，请重试");
      } finally {
        setLoading(false);
      }
    } else {
      const targetAgentId = agentId || state?.initAgentId || "";
      if (!targetAgentId) {
        antdMessage.warning("当前对话没有关联 Agent");
        return;
      }
      console.log("ask", message);
      await sendMessageToSession(message, targetAgentId, chatSessionId);
    }
  };

  useEffect(() => {
    if (!chatSessionId || !state?.init || !state.initMessage) {
      return;
    }

    const targetAgentId = state.initAgentId || agentId;
    if (!targetAgentId) {
      return;
    }

    const initKey = `${chatSessionId}:${targetAgentId}:${state.initMessage}`;
    if (initMessageSentRef.current === initKey) {
      return;
    }
    initMessageSentRef.current = initKey;
    setAgentId(targetAgentId);

    sendMessageToSession(state.initMessage, targetAgentId, chatSessionId).then(() => {
      navigate(`/chat/${chatSessionId}`, { replace: true, state: null });
    });
  }, [agentId, chatSessionId, navigate, sendMessageToSession, state]);

  useEffect(() => {
    if (!chatSessionId) {
      return;
    }
    const connectedChatSessionId = chatSessionId;
    const es = new EventSource(
      `http://localhost:8080/sse/connect/${connectedChatSessionId}`,
    );
    es.onmessage = (event) => {
      console.log("Received message:", event.data);
    };
    es.onerror = (error) => {
      console.error("SSE error:", error);
      if (
        activeChatSessionIdRef.current === connectedChatSessionId &&
        !currentRunHasAssistantContentRef.current
      ) {
        void getChatMessages(connectedChatSessionId);
      }
    };

    es.addEventListener("message", (event) => {
      if (activeChatSessionIdRef.current !== connectedChatSessionId) {
        return;
      }
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
        void refreshChatSessions();

        if (!finalMsg) {
          setIsStreaming(false);
          return;
        }

        if (finalMsg.role !== "assistant") {
          addMessage(finalMsg);
          setIsStreaming(false);
          setStreamingContent("");
          setDisplayAgentStatus(false);
          return;
        }

        // 先重置旧动画，新的回复会在思考状态至少展示一小段时间后开始打印
        resetTypewriter();
        setStreamingContent("");
        setIsStreaming(false);

        if (!content) {
          // 空内容直接提交
          addMessage(finalMsg);
          setIsStreaming(false);
          return;
        }

        // 入队 + 记录待提交消息 + 启动动画
        currentRunHasAssistantContentRef.current = true;
        charBufferRef.current = Array.from(content);
        pendingFinalMessageRef.current = finalMsg;
        startTypewriterAfterStatusDelay();
      } else if (message.type === "AI_PLANNING") {
        showAgentStatus("AI_PLANNING", message.payload.statusText);
      } else if (message.type === "AI_THINKING") {
        showAgentStatus("AI_THINKING", message.payload.statusText);
      } else if (message.type === "AI_EXECUTING") {
        showAgentStatus("AI_EXECUTING", message.payload.statusText);
      } else if (message.type === "AI_DONE") {
        if (currentRunHasAssistantContentRef.current) {
          return;
        } else {
          resetAssistantRuntimeState();
          void getChatMessages(connectedChatSessionId);
        }
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
      if (activeChatSessionIdRef.current === connectedChatSessionId) {
        resetAssistantRuntimeState();
      }
    };
  }, [
    chatSessionId,
    getChatMessages,
    refreshChatSessions,
    resetAssistantRuntimeState,
    resetTypewriter,
    showAgentStatus,
    startTypewriterAfterStatusDelay,
  ]);

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
    <div className="flex flex-col h-full bg-[var(--background)]">
      <div className="h-16 shrink-0 border-b border-[var(--border)] bg-[var(--background)] px-7 flex items-center">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-9 h-9 rounded-xl bg-[var(--brand-soft)] flex items-center justify-center text-base shrink-0 ring-1 ring-black/[.03]">
            {agentId ? getAgentEmoji(agentId) : "🤖"}
          </div>
          <div className="min-w-0">
            <div className="text-sm font-semibold text-zinc-900 truncate">
              {currentAgent?.name ?? "未知智能体"}
            </div>
            <div className="text-xs text-zinc-400 truncate max-w-[520px]">
              {currentAgent?.description || (agentId ? `Agent ID: ${agentId}` : "当前对话")}
            </div>
          </div>
        </div>
      </div>
      <AgentChatHistory
        messages={messages}
        streamingContent={streamingContent}
        isStreaming={isStreaming}
        displayAgentStatus={displayAgentStatus}
        agentStatusText={agentStatusText}
        agentStatusType={agentStatusType}
      />
      <div className="composer-dock px-6 pb-5 pt-2">
        {import.meta.env.DEV && (userMemories.length > 0 || agentMemories.length > 0) && (
          <div className="mb-3">
            <Collapse
              size="small"
              ghost
              items={[
                ...(userMemories.length > 0 ? [{
                  key: "user-memory-debug",
                  label: `本轮可注入 User Memory（${userMemories.length}）`,
                  children: (
                    <div className="space-y-2 text-xs text-zinc-500">
                      {userMemories.map((memory) => (
                        <div key={memory.id} className="rounded-md bg-amber-50 border border-amber-100 p-2">
                          <div className="font-medium text-amber-800">
                            [{memory.memoryType}] {memory.title}
                          </div>
                          <div className="whitespace-pre-wrap mt-1">{memory.content}</div>
                        </div>
                      ))}
                    </div>
                  ),
                }] : []),
                ...(agentMemories.length > 0 ? [{
                  key: "agent-memory-debug",
                  label: `本轮可注入 Agent Memory（${agentMemories.length}）`,
                  children: (
                    <div className="space-y-2 text-xs text-zinc-500">
                      {agentMemories.map((memory) => (
                        <div key={memory.id} className="rounded-md bg-zinc-50 border border-zinc-100 p-2">
                          <div className="font-medium text-zinc-700">
                            [{memory.memoryScope ?? "core"} / {memory.memoryType}] {memory.title}
                          </div>
                          <div className="whitespace-pre-wrap mt-1">{memory.content}</div>
                        </div>
                      ))}
                    </div>
                  ),
                }] : []),
              ]}
            />
          </div>
        )}
        <div className="mx-auto flex max-w-[820px] justify-end gap-2 mb-2 px-1 opacity-70 hover:opacity-100 transition-opacity">
          <Button size="small" onClick={() => openRememberModal("user")}>
            让所有 Agent 记住
          </Button>
          <Button size="small" onClick={() => openRememberModal("agent")} disabled={!agentId}>
            让当前 Agent 记住
          </Button>
        </div>
        <AgentChatInput onSend={handleSendMessage} />
      </div>
      <Modal
        open={memoryModalOpen}
        title={memoryTarget === "user" ? "让所有 Agent 记住" : "让当前 Agent 记住"}
        okText="保存记忆"
        cancelText="取消"
        onOk={saveMemory}
        onCancel={() => setMemoryModalOpen(false)}
      >
        <div className="space-y-3">
          <Input
            placeholder="记忆标题，例如：用户备考方向"
            value={memoryDraft.title}
            onChange={(event) =>
              setMemoryDraft({ ...memoryDraft, title: event.target.value })
            }
          />
          <Select
            className="w-full"
            value={memoryDraft.memoryType}
            onChange={(value) =>
              setMemoryDraft({ ...memoryDraft, memoryType: value })
            }
            options={
              memoryTarget === "user"
                ? [
                    { value: "preference", label: "偏好" },
                    { value: "profile", label: "用户背景" },
                    { value: "communication", label: "沟通习惯" },
                    { value: "constraint", label: "约束" },
                  ]
                : [
                    { value: "fact", label: "事实" },
                    { value: "preference", label: "偏好" },
                    { value: "task", label: "任务" },
                    { value: "feedback", label: "反馈" },
                    { value: "decision", label: "决策" },
                  ]
            }
          />
          <Input.TextArea
            rows={5}
            placeholder={
              memoryTarget === "user"
                ? "用户级记忆会被所有 Agent 参考。只保存跨 Agent 都稳定有效的偏好、背景或沟通习惯。"
                : "记忆内容。建议只保存长期有效、只适用于当前 Agent 的信息。"
            }
            value={memoryDraft.content}
            onChange={(event) =>
              setMemoryDraft({ ...memoryDraft, content: event.target.value })
            }
          />
        </div>
      </Modal>
    </div>
  );
};

export default AgentChatView;
