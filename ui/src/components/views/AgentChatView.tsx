import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import { Button, Modal, Input, Select, Collapse, message as antdMessage } from "antd";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createAgentMemory,
  createChatMessage,
  createChatSession,
  getAgentMemories,
  getChatMessagesBySessionId,
  getChatSession,
  type AgentMemoryVO,
} from "../../api/api.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";
import { getAgentEmoji } from "../../utils";

const AgentChatView: React.FC = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const navigate = useNavigate();
  const { state } = useLocation();
  const [loading, setLoading] = useState(false);
  const { agents } = useAgents();
  const { refreshChatSessions } = useChatSessions();

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [agentMemories, setAgentMemories] = useState<AgentMemoryVO[]>([]);
  const [memoryModalOpen, setMemoryModalOpen] = useState(false);
  const [memoryDraft, setMemoryDraft] = useState({
    title: "",
    content: "",
    memoryType: "fact",
  });

  // 流式输出状态（后端响应完成后一次性下发，前端做“假流式”逐字打印）
  const [streamingContent, setStreamingContent] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);

  // 逐字打印动画状态
  const charBufferRef = useRef<string[]>([]);
  const rafIdRef = useRef<number | null>(null);
  const lastFrameTimeRef = useRef<number>(0);
  const pendingFinalMessageRef = useRef<ChatMessageVO | null>(null);
  const initMessageSentRef = useRef<string | null>(null);

  const addMessage = (message: ChatMessageVO) => {
    setMessages((prevMessages) => [...prevMessages, message]);
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

  const showAssistantWaiting = useCallback(() => {
    resetTypewriter();
    setStreamingContent("");
    setIsStreaming(true);
    setDisplayAgentStatus(false);
    setAgentStatusText("");
    setAgentStatusType(undefined);
  }, [resetTypewriter]);

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

  useEffect(() => {
    refreshAgentMemories().catch((error) => {
      console.error("获取 Agent 记忆失败:", error);
    });
  }, [refreshAgentMemories]);

  const openRememberModal = () => {
    const lastUserMessage = [...messages].reverse().find((item) => item.role === "user");
    setMemoryDraft({
      title: "",
      content: lastUserMessage?.content ?? "",
      memoryType: "fact",
    });
    setMemoryModalOpen(true);
  };

  const saveAgentMemory = async () => {
    if (!agentId) {
      antdMessage.warning("当前对话没有关联 Agent");
      return;
    }
    if (!memoryDraft.title.trim() || !memoryDraft.content.trim()) {
      antdMessage.warning("请填写记忆标题和内容");
      return;
    }
    await createAgentMemory(agentId, {
      title: memoryDraft.title.trim(),
      content: memoryDraft.content.trim(),
      memoryType: memoryDraft.memoryType,
      enabled: true,
      priority: 0,
    });
    setMemoryModalOpen(false);
    setMemoryDraft({ title: "", content: "", memoryType: "fact" });
    await refreshAgentMemories();
    antdMessage.success("当前 Agent 已记住该内容");
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
        });
      } catch (error) {
        console.error("发送聊天消息失败:", error);
        removeMessage(optimisticId);
        setIsStreaming(false);
        setStreamingContent("");
        antdMessage.error("消息发送失败，请重试");
      }
    },
    [showAssistantWaiting],
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

  const [displayAgentStatus, setDisplayAgentStatus] = useState<boolean>(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<
    SseMessageType | undefined
  >(undefined);

  useEffect(() => {
    if (!chatSessionId || !state?.init || !state.initMessage) {
      return;
    }

    // 等待会话详情加载完成后再发送首条消息，避免初始化消息拉取覆盖乐观 UI。
    if (!agentId) {
      return;
    }

    const initKey = `${chatSessionId}:${state.initMessage}`;
    if (initMessageSentRef.current === initKey) {
      return;
    }
    initMessageSentRef.current = initKey;

    sendMessageToSession(state.initMessage, agentId, chatSessionId).then(() => {
      navigate(`/chat/${chatSessionId}`, { replace: true, state: null });
    });
  }, [agentId, chatSessionId, navigate, sendMessageToSession, state]);

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
      <div className="h-14 shrink-0 border-b border-zinc-100 bg-white px-5 flex items-center">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-9 h-9 rounded-xl bg-indigo-50 flex items-center justify-center text-base shrink-0">
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
      <div className="border-t border-zinc-100 p-4 bg-white">
        {import.meta.env.DEV && agentMemories.length > 0 && (
          <div className="mb-3">
            <Collapse
              size="small"
              ghost
              items={[
                {
                  key: "agent-memory-debug",
                  label: `本轮可注入 Agent Memory（${agentMemories.length}）`,
                  children: (
                    <div className="space-y-2 text-xs text-zinc-500">
                      {agentMemories.map((memory) => (
                        <div key={memory.id} className="rounded-md bg-zinc-50 border border-zinc-100 p-2">
                          <div className="font-medium text-zinc-700">
                            [{memory.memoryType}] {memory.title}
                          </div>
                          <div className="whitespace-pre-wrap mt-1">{memory.content}</div>
                        </div>
                      ))}
                    </div>
                  ),
                },
              ]}
            />
          </div>
        )}
        <div className="flex justify-end mb-2">
          <Button size="small" onClick={openRememberModal} disabled={!agentId}>
            让当前 Agent 记住
          </Button>
        </div>
        <AgentChatInput onSend={handleSendMessage} />
      </div>
      <Modal
        open={memoryModalOpen}
        title="让当前 Agent 记住"
        okText="保存记忆"
        cancelText="取消"
        onOk={saveAgentMemory}
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
            options={[
              { value: "fact", label: "事实" },
              { value: "preference", label: "偏好" },
              { value: "task", label: "任务" },
              { value: "feedback", label: "反馈" },
              { value: "decision", label: "决策" },
            ]}
          />
          <Input.TextArea
            rows={5}
            placeholder="记忆内容。建议只保存长期有效、只适用于当前 Agent 的信息。"
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
