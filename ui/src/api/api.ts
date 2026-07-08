import { get, post, patch, del, BASE_URL } from "./http.ts";
import type { ChatMessageVO, MessageType } from "../types";

// 类型定义
export interface ChatOptions {
  temperature?: number;
  topP?: number;
  messageLength?: number;
}

export type ModelType = "deepseek-chat" | "glm-4.6";

export interface CreateAgentRequest {
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
  autoMemoryEnabled?: boolean;
  autoMemoryInterval?: number;
}

export interface UpdateAgentRequest {
  name?: string;
  description?: string;
  systemPrompt?: string;
  model?: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
}

export interface CreateAgentResponse {
  agentId: string;
}

export interface AgentVO {
  id: string;
  name: string;
  description?: string;
  systemPrompt?: string;
  model: ModelType;
  allowedTools?: string[];
  allowedKbs?: string[];
  chatOptions?: ChatOptions;
  autoMemoryEnabled?: boolean;
  autoMemoryInterval?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface GetAgentsResponse {
  agents: AgentVO[];
}

export interface AgentMemoryVO {
  id: string;
  agentId: string;
  sourceMessageId?: string;
  memoryScope?: string;
  memoryType: string;
  title: string;
  content: string;
  priority: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  lastUsedAt?: string;
}

export interface GetAgentMemoriesResponse {
  agentMemories: AgentMemoryVO[];
}

export interface CreateAgentMemoryRequest {
  sourceMessageId?: string;
  memoryScope?: string;
  memoryType?: string;
  title: string;
  content: string;
  priority?: number;
  enabled?: boolean;
}

export interface CreateAgentMemoryResponse {
  agentMemoryId: string;
}

export interface UpdateAgentMemoryRequest {
  memoryScope?: string;
  memoryType?: string;
  title?: string;
  content?: string;
  priority?: number;
  enabled?: boolean;
}

export interface UserMemoryVO {
  id: string;
  userId?: string;
  sourceMessageId?: string;
  memoryType: string;
  title: string;
  content: string;
  priority: number;
  confidence: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  lastUsedAt?: string;
}

export interface GetUserMemoriesResponse {
  userMemories: UserMemoryVO[];
}

export interface CreateUserMemoryRequest {
  userId?: string;
  sourceMessageId?: string;
  memoryType?: string;
  title: string;
  content: string;
  priority?: number;
  confidence?: number;
  enabled?: boolean;
}

export interface CreateUserMemoryResponse {
  userMemoryId: string;
}

export interface UpdateUserMemoryRequest {
  memoryType?: string;
  title?: string;
  content?: string;
  priority?: number;
  confidence?: number;
  enabled?: boolean;
}

/**
 * 获取所有 agents
 */
export async function getAgents(): Promise<GetAgentsResponse> {
  return get<GetAgentsResponse>("/agents");
}

/**
 * 创建 agent
 */
export async function createAgent(
  request: CreateAgentRequest,
): Promise<CreateAgentResponse> {
  return post<CreateAgentResponse>("/agents", request);
}

/**
 * 删除 agent
 */
export async function deleteAgent(agentId: string): Promise<void> {
  return del<void>(`/agents/${agentId}`);
}

/**
 * 更新 agent
 */
export async function updateAgent(
  agentId: string,
  request: UpdateAgentRequest,
): Promise<void> {
  return patch<void>(`/agents/${agentId}`, request);
}

export async function getAgentMemories(
  agentId: string,
): Promise<GetAgentMemoriesResponse> {
  return get<GetAgentMemoriesResponse>(`/agents/${agentId}/memories`);
}

export async function createAgentMemory(
  agentId: string,
  request: CreateAgentMemoryRequest,
): Promise<CreateAgentMemoryResponse> {
  return post<CreateAgentMemoryResponse>(`/agents/${agentId}/memories`, request);
}

export async function updateAgentMemory(
  memoryId: string,
  request: UpdateAgentMemoryRequest,
): Promise<void> {
  return patch<void>(`/agent-memories/${memoryId}`, request);
}

export async function deleteAgentMemory(memoryId: string): Promise<void> {
  return del<void>(`/agent-memories/${memoryId}`);
}

export async function getUserMemories(): Promise<GetUserMemoriesResponse> {
  return get<GetUserMemoriesResponse>("/user-memories");
}

export async function createUserMemory(
  request: CreateUserMemoryRequest,
): Promise<CreateUserMemoryResponse> {
  return post<CreateUserMemoryResponse>("/user-memories", request);
}

export async function updateUserMemory(
  memoryId: string,
  request: UpdateUserMemoryRequest,
): Promise<void> {
  return patch<void>(`/user-memories/${memoryId}`, request);
}

export async function deleteUserMemory(memoryId: string): Promise<void> {
  return del<void>(`/user-memories/${memoryId}`);
}

/**
 * 创建聊天会话
 */
export interface CreateChatSessionRequest {
  agentId: string;
  title?: string;
}

export interface CreateChatSessionResponse {
  chatSessionId: string;
}

export async function createChatSession(
  request: CreateChatSessionRequest,
): Promise<CreateChatSessionResponse> {
  return post<CreateChatSessionResponse>("/chat-sessions", request);
}

/**
 * 聊天会话相关类型和接口
 */
export interface ChatSessionVO {
  id: string;
  agentId?: string | null;
  title?: string;
  pinned?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface GetChatSessionsResponse {
  chatSessions: ChatSessionVO[];
}

export interface GetChatSessionResponse {
  chatSession: ChatSessionVO;
}

export interface UpdateChatSessionRequest {
  title?: string;
  pinned?: boolean;
}

/**
 * 获取所有聊天会话
 */
export async function getChatSessions(): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>("/chat-sessions");
}

/**
 * 获取单个聊天会话
 */
export async function getChatSession(
  chatSessionId: string,
): Promise<GetChatSessionResponse> {
  return get<GetChatSessionResponse>(`/chat-sessions/${chatSessionId}`);
}

/**
 * 根据 agentId 获取聊天会话
 */
export async function getChatSessionsByAgentId(
  agentId: string,
): Promise<GetChatSessionsResponse> {
  return get<GetChatSessionsResponse>(`/chat-sessions/agent/${agentId}`);
}

/**
 * 更新聊天会话
 */
export async function updateChatSession(
  chatSessionId: string,
  request: UpdateChatSessionRequest,
): Promise<void> {
  return patch<void>(`/chat-sessions/${chatSessionId}`, request);
}

/**
 * 批量删除聊天会话
 */
export async function deleteChatSessions(chatSessionIds: string[]): Promise<void> {
  return post<void>("/chat-sessions/batch-delete", { chatSessionIds });
}

/**
 * 删除聊天会话
 */
export async function deleteChatSession(chatSessionId: string): Promise<void> {
  return del<void>(`/chat-sessions/${chatSessionId}`);
}

/**
 * 聊天消息相关类型和接口
 */
export interface MetaData {
  [key: string]: unknown;
}

export interface GetChatMessagesResponse {
  chatMessages: ChatMessageVO[];
}

export interface CreateChatMessageRequest {
  agentId: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: MetaData;
}

export interface CreateChatMessageResponse {
  chatMessageId: string;
}

export interface UpdateChatMessageRequest {
  content?: string;
  metadata?: MetaData;
}

/**
 * 根据 sessionId 获取聊天消息
 */
export async function getChatMessagesBySessionId(
  sessionId: string,
): Promise<GetChatMessagesResponse> {
  return get<GetChatMessagesResponse>(`/chat-messages/session/${sessionId}`);
}

/**
 * 创建聊天消息
 */
export async function createChatMessage(
  request: CreateChatMessageRequest,
): Promise<CreateChatMessageResponse> {
  return post<CreateChatMessageResponse>("/chat-messages", request);
}

/**
 * 更新聊天消息
 */
export async function updateChatMessage(
  chatMessageId: string,
  request: UpdateChatMessageRequest,
): Promise<void> {
  return patch<void>(`/chat-messages/${chatMessageId}`, request);
}

/**
 * 删除聊天消息
 */
export async function deleteChatMessage(chatMessageId: string): Promise<void> {
  return del<void>(`/chat-messages/${chatMessageId}`);
}

/**
 * 知识库相关类型和接口
 */
export interface KnowledgeBaseVO {
  id: string;
  name: string;
  description?: string;
}

export interface CreateKnowledgeBaseRequest {
  name: string;
  description?: string;
}

export interface UpdateKnowledgeBaseRequest {
  name?: string;
  description?: string;
}

export interface GetKnowledgeBasesResponse {
  knowledgeBases: KnowledgeBaseVO[];
}

export interface CreateKnowledgeBaseResponse {
  knowledgeBaseId: string;
}

/**
 * 获取所有知识库
 */
export async function getKnowledgeBases(): Promise<GetKnowledgeBasesResponse> {
  return get<GetKnowledgeBasesResponse>("/knowledge-bases");
}

/**
 * 创建知识库
 */
export async function createKnowledgeBase(
  request: CreateKnowledgeBaseRequest,
): Promise<CreateKnowledgeBaseResponse> {
  return post<CreateKnowledgeBaseResponse>("/knowledge-bases", request);
}

/**
 * 删除知识库
 */
export async function deleteKnowledgeBase(
  knowledgeBaseId: string,
): Promise<void> {
  return del<void>(`/knowledge-bases/${knowledgeBaseId}`);
}

/**
 * 更新知识库
 */
export async function updateKnowledgeBase(
  knowledgeBaseId: string,
  request: UpdateKnowledgeBaseRequest,
): Promise<void> {
  return patch<void>(`/knowledge-bases/${knowledgeBaseId}`, request);
}

/**
 * 文档相关类型和接口
 */
export interface DocumentVO {
  id: string;
  kbId: string;
  filename: string;
  filetype: string;
  size: number;
  metadata?: {
    filePath?: string;
    markdownPath?: string;
    originalFileType?: string;
    conversionTool?: string;
    conversionStatus?: string;
    conversionError?: string;
  };
}

export interface GetDocumentsResponse {
  documents: DocumentVO[];
}

export interface CreateDocumentResponse {
  documentId: string;
}

/**
 * 根据知识库 ID 获取文档列表
 */
export async function getDocumentsByKbId(
  kbId: string,
): Promise<GetDocumentsResponse> {
  return get<GetDocumentsResponse>(`/documents/kb/${kbId}`);
}

/**
 * 上传文档
 */
export async function uploadDocument(
  kbId: string,
  file: File,
): Promise<CreateDocumentResponse> {
  const formData = new FormData();
  formData.append("kbId", kbId);
  formData.append("file", file);

  const response = await fetch(`${BASE_URL}/documents/upload`, {
    method: "POST",
    body: formData,
  });

  if (!response.ok) {
    const errorText = await response.text().catch(() => "");
    throw new Error(errorText || `HTTP error! status: ${response.status}`);
  }

  const apiResponse = await response.json();
  if (apiResponse.code !== 200) {
    throw new Error(apiResponse.message || "上传失败");
  }

  return apiResponse.data;
}

/**
 * 删除文档
 */
export async function deleteDocument(documentId: string): Promise<void> {
  return del<void>(`/documents/${documentId}`);
}

/**
 * 工具相关类型和接口
 */
export type ToolType = "FIXED" | "OPTIONAL";

export interface ToolVO {
  name: string;
  description: string;
  type: ToolType;
}

export interface GetOptionalToolsResponse {
  tools: ToolVO[];
}

/**
 * 获取可选工具列表
 */
export async function getOptionalTools(): Promise<GetOptionalToolsResponse> {
  const tools = await get<ToolVO[]>("/tools");
  return { tools };
}
