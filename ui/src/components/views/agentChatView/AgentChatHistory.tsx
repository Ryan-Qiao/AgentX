import React, { useState, useRef, useEffect, useCallback } from "react";
import { Bubble } from "@ant-design/x";
import XMarkdown from "@ant-design/x-markdown";
import {
  CheckCircleOutlined,
  RobotOutlined,
  DownOutlined,
  RightOutlined,
} from "@ant-design/icons";
import type { ChatMessageVO, SseMessageType, ToolResponse } from "../../../types";

interface KnowledgeHit {
  rank: number;
  documentTitle: string;
  documentId: string;
  chunkId: string;
  distance: string;
  score: string;
  rerankScore: string;
  content: string;
}

interface AgentChatHistoryProps {
  messages: ChatMessageVO[];
  streamingContent?: string;
  isStreaming?: boolean;
  displayAgentStatus?: boolean;
  agentStatusText?: string;
  agentStatusType?: SseMessageType;
}

const stripDsmlToolCalls = (content: string) =>
  content
    .replace(
      /<[｜|]\s*[｜|]DSML[｜|]\s*[｜|]tool_calls>[\s\S]*?<\/[｜|]\s*[｜|]DSML[｜|]\s*[｜|]tool_calls>/g,
      "",
    )
    .trim();

const parseKnowledgeToolResponse = (responseData: string): KnowledgeHit[] => {
  const pattern =
    /\[(\d+)\] 文档：(.+?)\ndocumentId：(.+?)\nchunkId：(.+?)\ndistance：(.+?)\nscore：(.+?)\nrerankScore：(.+?)\n内容：\n([\s\S]*?)(?=\n\n\[\d+\] 文档：|\n\n使用规则：|$)/g;
  return Array.from(responseData.matchAll(pattern)).map((match) => ({
    rank: Number(match[1]),
    documentTitle: match[2].trim(),
    documentId: match[3].trim(),
    chunkId: match[4].trim(),
    distance: match[5].trim(),
    score: match[6].trim(),
    rerankScore: match[7].trim(),
    content: match[8].trim(),
  }));
};

const ToolResponseDisplay: React.FC<{ toolResponse: ToolResponse }> = ({
  toolResponse,
}) => {
  const [expanded, setExpanded] = useState(false);
  
  let parsedData: unknown = null;
  let isJson = false;
  let dataPreview = "";
  const knowledgeHits =
    toolResponse.name === "KnowledgeTool"
      ? parseKnowledgeToolResponse(toolResponse.responseData)
      : [];
  
  try {
    parsedData = JSON.parse(toolResponse.responseData);
    isJson = true;
    const jsonStr = JSON.stringify(parsedData);
    dataPreview = jsonStr.length > 100 ? jsonStr.slice(0, 100) + "..." : jsonStr;
  } catch {
    if (knowledgeHits.length > 0) {
      dataPreview = `命中 ${knowledgeHits.length} 个片段：${knowledgeHits
        .map((hit) => hit.documentTitle)
        .join("、")}`;
    } else {
      dataPreview = toolResponse.responseData.length > 100 
        ? toolResponse.responseData.slice(0, 100) + "..." 
        : toolResponse.responseData;
    }
  }

  return (
    <div className="my-1.5 text-xs">
      <div 
        className="flex items-center gap-2 text-zinc-500 cursor-pointer hover:text-zinc-700 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? (
          <DownOutlined className="text-zinc-400" />
        ) : (
          <RightOutlined className="text-zinc-400" />
        )}
        <CheckCircleOutlined className="text-emerald-500" />
        <span className="font-mono text-emerald-600">{toolResponse.name}</span>
        <span className="text-zinc-300">·</span>
        <span className="text-zinc-400 truncate flex-1">{dataPreview}</span>
      </div>
      {expanded && (
        <div className="ml-5 mt-1.5 p-2 bg-zinc-50 rounded border border-zinc-200">
          <div className="text-xs text-zinc-600 font-mono">
            {knowledgeHits.length > 0 ? (
              <div className="space-y-2 font-sans">
                {knowledgeHits.map((hit) => (
                  <div
                    key={`${hit.rank}-${hit.chunkId}`}
                    className="rounded border border-zinc-200 bg-white p-2"
                  >
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-zinc-700">
                      <span className="font-semibold">[{hit.rank}] {hit.documentTitle}</span>
                      <span className="text-zinc-300">·</span>
                      <span>distance {hit.distance}</span>
                      <span>score {hit.score}</span>
                      <span>rerank {hit.rerankScore}</span>
                    </div>
                    <div className="mt-1 text-[11px] text-zinc-400 break-all">
                      {hit.chunkId}
                    </div>
                    <div className="mt-2 whitespace-pre-wrap break-words leading-relaxed text-zinc-600">
                      {hit.content}
                    </div>
                  </div>
                ))}
              </div>
            ) : isJson ? (
              <pre className="whitespace-pre-wrap break-words overflow-x-auto max-h-60 overflow-y-auto">
                {JSON.stringify(parsedData, null, 2)}
              </pre>
            ) : (
              <div className="whitespace-pre-wrap break-words">
                {toolResponse.responseData}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

/** 跳动三点指示器 */
const BouncingDots: React.FC = () => {
  return (
    <span className="inline-flex items-center gap-[3px]">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="w-[5px] h-[5px] rounded-full bg-zinc-400 animate-bounce"
          style={{
            animation: `bounce 1.2s ease-in-out infinite`,
            animationDelay: `${i * 0.2}s`,
          }}
        />
      ))}
    </span>
  );
};

const AgentChatHistory: React.FC<AgentChatHistoryProps> = ({
  messages,
  streamingContent = "",
  isStreaming = false,
  displayAgentStatus = false,
  agentStatusText = "",
  agentStatusType,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [isNearBottom, setIsNearBottom] = useState(true);
  const SCROLL_THRESHOLD = 20;
  const prevMessagesLengthRef = useRef(messages.length);

  const checkIfNearBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return false;
    const { scrollTop, clientHeight, scrollHeight } = container;
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
    return distanceFromBottom <= SCROLL_THRESHOLD;
  }, []);

  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return;
    requestAnimationFrame(() => {
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    });
  }, []);

  const handleScroll = useCallback(() => {
    const nearBottom = checkIfNearBottom();
    setIsNearBottom(nearBottom);
  }, [checkIfNearBottom]);

  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;
    const initTimer = setTimeout(() => {
      setIsNearBottom(checkIfNearBottom());
    }, 0);
    container.addEventListener("scroll", handleScroll, { passive: true });
    return () => {
      clearTimeout(initTimer);
      container.removeEventListener("scroll", handleScroll);
    };
  }, [handleScroll, checkIfNearBottom]);

  // 监听消息长度变化→自动滚动
  useEffect(() => {
    const hasNewMessage = messages.length > prevMessagesLengthRef.current;
    prevMessagesLengthRef.current = messages.length;
    if (hasNewMessage && isNearBottom) {
      scrollToBottom();
    }
  }, [messages, isNearBottom, scrollToBottom]);

  // 流式内容变化→自动滚动
  useEffect(() => {
    if (isStreaming && isNearBottom) {
      scrollToBottom();
    }
  }, [streamingContent, isStreaming, isNearBottom, scrollToBottom]);

  useEffect(() => {
    if (displayAgentStatus && isNearBottom) {
      scrollToBottom();
    }
  }, [displayAgentStatus, isNearBottom, scrollToBottom]);

  const getStatusLabel = () => {
    switch (agentStatusType) {
      case "AI_PLANNING":
        return "规划中";
      case "AI_THINKING":
        return "思考中";
      case "AI_EXECUTING":
        return "执行中";
      default:
        return "处理中";
    }
  };

  return (
    <div 
      ref={scrollContainerRef}
      className="flex-1 px-8 pt-4 overflow-y-scroll"
    >
      {messages.map((message) => {
        const visibleAssistantContent =
          message.role === "assistant" ? stripDsmlToolCalls(message.content ?? "") : "";

        return (
          <div className="mb-4" key={message.id}>
            {message.role === "assistant" && Boolean(visibleAssistantContent) && (
              <Bubble
                content={
                  <div className="w-full">
                    <div>
                      <XMarkdown
                        streaming={{ enableAnimation: false, hasNextChunk: true }}
                      >
                        {visibleAssistantContent}
                      </XMarkdown>
                    </div>
                  </div>
                }
                placement="start"
              />
            )}

            {message.role === "tool" && message.metadata?.toolResponse && (
              <div className="flex justify-start">
                <div className="max-w-[85%]">
                  <ToolResponseDisplay toolResponse={message.metadata.toolResponse} />
                </div>
              </div>
            )}

            {message.role === "user" && (
              <Bubble content={message.content} placement="end" />
            )}

            {message.role === "system" && (
              <div className="flex justify-center">
                <div className="px-3 py-1 bg-zinc-100 text-zinc-500 text-xs rounded-full flex items-center gap-1">
                  <RobotOutlined />
                  <span>{message.content}</span>
                </div>
              </div>
            )}
          </div>
        );
      })}

      {/* 流式输出区域 */}
      {isStreaming && (
        <div className="mb-4">
          <Bubble
            content={
              streamingContent ? (
                <XMarkdown streaming={{ enableAnimation: false, hasNextChunk: true }}>
                  {streamingContent}
                </XMarkdown>
              ) : (
                <span className="flex items-center gap-2 text-zinc-500 text-sm">
                  思考中
                  <BouncingDots />
                </span>
              )
            }
            placement="start"
          />
        </div>
      )}

      {/* Agent 状态（规划中/思考中/执行中）—— 等待首字期间也需要展示工具进度 */}
      {displayAgentStatus && (!isStreaming || !streamingContent) && (
        <div className="mb-3 animate-pulse">
          <Bubble
            content={
              <span className="flex items-center gap-2">
                <span className="font-medium text-indigo-500 text-sm">
                  {getStatusLabel()}
                </span>
                <span className="text-zinc-300">·</span>
                <span className="text-zinc-500 text-sm">{agentStatusText}</span>
                <BouncingDots />
              </span>
            }
            placement="start"
          />
        </div>
      )}
    </div>
  );
};

export default AgentChatHistory;
