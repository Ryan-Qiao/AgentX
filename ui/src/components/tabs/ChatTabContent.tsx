import React, { useMemo, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Button, Checkbox, Popconfirm, Tooltip, message } from "antd";
import type { CheckboxChangeEvent } from "antd/es/checkbox";
import {
  CaretDownOutlined,
  CaretRightOutlined,
  DeleteOutlined,
  MessageOutlined,
  PlusOutlined,
  PushpinFilled,
  PushpinOutlined,
} from "@ant-design/icons";
import { useChatSessions } from "../../hooks/useChatSessions.ts";
import { useAgents } from "../../hooks/useAgents.ts";
import type { ChatSessionVO } from "../../api/api.ts";
import { formatDateTime, getAgentEmoji } from "../../utils";

interface AgentGroup {
  key: string;
  agentId?: string | null;
  label: string;
  sessions: ChatSessionVO[];
}

const DELETED_AGENT_KEY = "__deleted_agent__";

const ChatTabContent: React.FC = () => {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const {
    chatSessions,
    loading,
    deleteChatSession,
    deleteChatSessions,
    updateChatSession,
  } = useChatSessions();
  const { agents } = useAgents();
  const [manageMode, setManageMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(
    () => new Set(),
  );

  const agentMap = useMemo(() => {
    const map = new Map<string, { name: string; description?: string }>();
    agents.forEach((agent) => {
      map.set(agent.id, {
        name: agent.name,
        description: agent.description,
      });
    });
    return map;
  }, [agents]);

  const selectedIdSet = useMemo(() => new Set(selectedIds), [selectedIds]);

  const groupedSessions = useMemo<AgentGroup[]>(() => {
    const groups = new Map<string, AgentGroup>();

    const sortSessions = (sessions: ChatSessionVO[]) => {
      return [...sessions].sort((a, b) => {
        const aTime = new Date(a.updatedAt ?? a.createdAt ?? 0).getTime();
        const bTime = new Date(b.updatedAt ?? b.createdAt ?? 0).getTime();
        return bTime - aTime;
      });
    };

    chatSessions
      .filter((session) => !session.pinned)
      .forEach((session) => {
        const key = session.agentId ?? DELETED_AGENT_KEY;
        const label = session.agentId
          ? agentMap.get(session.agentId)?.name ?? "已删除的智能体"
          : "已删除的智能体";

        if (!groups.has(key)) {
          groups.set(key, {
            key,
            agentId: session.agentId,
            label,
            sessions: [],
          });
        }
        groups.get(key)?.sessions.push(session);
      });

    return Array.from(groups.values())
      .map((group) => ({
        ...group,
        sessions: sortSessions(group.sessions),
      }))
      .sort((a, b) => {
        const aTime = new Date(
          a.sessions[0]?.updatedAt ?? a.sessions[0]?.createdAt ?? 0,
        ).getTime();
        const bTime = new Date(
          b.sessions[0]?.updatedAt ?? b.sessions[0]?.createdAt ?? 0,
        ).getTime();
        return bTime - aTime;
      });
  }, [agentMap, chatSessions]);

  const pinnedSessions = useMemo(() => {
    return chatSessions
      .filter((session) => session.pinned)
      .sort((a, b) => {
        const aTime = new Date(a.updatedAt ?? a.createdAt ?? 0).getTime();
        const bTime = new Date(b.updatedAt ?? b.createdAt ?? 0).getTime();
        return bTime - aTime;
      });
  }, [chatSessions]);

  const allSessionIds = useMemo(
    () => chatSessions.map((session) => session.id),
    [chatSessions],
  );

  const handleCreateNewChat = () => {
    navigate("/chat");
  };

  const handleSelectChatSession = (chatSessionId: string) => {
    if (manageMode) {
      toggleSelect(chatSessionId);
      return;
    }
    navigate(`/chat/${chatSessionId}`);
  };

  const handleDeleteChatSession = async (chatSessionId: string) => {
    await deleteChatSession(chatSessionId);
    setSelectedIds((prev) => prev.filter((id) => id !== chatSessionId));
    if (pathname === `/chat/${chatSessionId}`) {
      navigate("/chat");
    }
  };

  const handleBatchDelete = async () => {
    const idsToDelete = selectedIds;
    await deleteChatSessions(idsToDelete);
    setSelectedIds([]);
    setManageMode(false);
    if (idsToDelete.some((id) => pathname === `/chat/${id}`)) {
      navigate("/chat");
    }
  };

  const togglePinned = async (session: ChatSessionVO) => {
    await updateChatSession(session.id, { pinned: !session.pinned });
    message.success(session.pinned ? "已取消置顶" : "已置顶");
  };

  const toggleSelect = (chatSessionId: string) => {
    setSelectedIds((prev) =>
      prev.includes(chatSessionId)
        ? prev.filter((id) => id !== chatSessionId)
        : [...prev, chatSessionId],
    );
  };

  const toggleAll = (event: CheckboxChangeEvent) => {
    setSelectedIds(event.target.checked ? allSessionIds : []);
  };

  const toggleGroup = (groupKey: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(groupKey)) {
        next.delete(groupKey);
      } else {
        next.add(groupKey);
      }
      return next;
    });
  };

  const getDisplayTitle = (session: ChatSessionVO) => {
    if (session.title) {
      return session.title;
    }
    if (!session.agentId) {
      return "历史对话";
    }
    const agent = agentMap.get(session.agentId);
    return agent ? `与 ${agent.name} 的对话` : "新对话";
  };

  const getAgentLabel = (agentId?: string | null) => {
    if (!agentId) {
      return "已删除的智能体";
    }
    return agentMap.get(agentId)?.name ?? "已删除的智能体";
  };

  const selectedCount = selectedIds.length;
  const checkedAll = selectedCount > 0 && selectedCount === allSessionIds.length;
  const indeterminate = selectedCount > 0 && selectedCount < allSessionIds.length;

  const renderSessionItem = (
    session: ChatSessionVO,
    options: { showAgentDetails: boolean; separated?: boolean },
  ) => {
    const active = pathname === `/chat/${session.id}`;
    const selected = selectedIdSet.has(session.id);
    const agentLabel = getAgentLabel(session.agentId);
    const itemClassName = options.separated
      ? `w-full px-3 py-3 cursor-pointer transition-all group relative rounded-lg ${
          active
            ? "bg-[var(--brand-soft)]/65 ring-1 ring-indigo-100/70"
            : selected
              ? "bg-zinc-50 ring-1 ring-zinc-200"
              : "hover:bg-zinc-50 active:bg-zinc-100"
        }`
      : `w-full px-3 py-2.5 cursor-pointer transition-all group relative rounded-xl border ${
          active
            ? "bg-indigo-50/80 border-indigo-100 shadow-sm"
            : selected
              ? "bg-zinc-50 border-zinc-200"
              : "border-transparent hover:bg-zinc-50 active:bg-zinc-100"
        }`;

    return (
      <div
        key={session.id}
        onClick={() => handleSelectChatSession(session.id)}
        className={itemClassName}
      >
        <div className="flex items-start gap-2.5">
          {manageMode && (
            <div
              onClick={(event) => event.stopPropagation()}
              className="mt-1.5 shrink-0"
            >
              <Checkbox
                checked={selected}
                onChange={() => toggleSelect(session.id)}
              />
            </div>
          )}
          {options.showAgentDetails && (
            <div
              className={`w-8 h-8 rounded-xl flex items-center justify-center shrink-0 mt-0.5 text-sm ring-1 ${
                active
                  ? "bg-white ring-indigo-100"
                  : "bg-gradient-to-br from-zinc-50 to-indigo-50 ring-zinc-100"
              }`}
              title={agentLabel}
            >
              {getAgentEmoji(session.agentId)}
            </div>
          )}
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-1.5 min-w-0">
              {session.pinned && (
                <PushpinFilled className="shrink-0 text-[11px] text-amber-500" />
              )}
              <div
                className={`font-medium text-sm truncate ${
                  active ? "text-indigo-950" : "text-zinc-900"
                }`}
                title={getDisplayTitle(session)}
              >
                {getDisplayTitle(session)}
              </div>
            </div>
            <div className="mt-1 flex items-center gap-1.5 min-w-0">
              {options.showAgentDetails && (
                <span
                  className={`max-w-full truncate rounded-full px-2 py-0.5 text-[11px] leading-4 ring-1 ${
                    active
                      ? "bg-white/80 text-indigo-700 ring-indigo-100"
                      : "bg-zinc-50 text-zinc-500 ring-zinc-100"
                  }`}
                  title={`使用 Agent：${agentLabel}`}
                >
                  {agentLabel}
                </span>
              )}
              {session.updatedAt && (
                <span className="shrink-0 text-[11px] text-zinc-400">
                  {formatDateTime(session.updatedAt)}
                </span>
              )}
            </div>
          </div>
          <div
            onClick={(event) => event.stopPropagation()}
            className="mt-0.5 flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100"
          >
            <Tooltip title={session.pinned ? "取消置顶" : "置顶"}>
              <Button
                type="text"
                size="small"
                icon={session.pinned ? <PushpinFilled /> : <PushpinOutlined />}
                onClick={() => togglePinned(session)}
                className={
                  session.pinned
                    ? "text-amber-500 hover:text-amber-600"
                    : "text-zinc-400 hover:text-zinc-600"
                }
              />
            </Tooltip>
            <Popconfirm
              title="确定要删除这条聊天记录吗？"
              description="删除后将无法恢复"
              onConfirm={() => handleDeleteChatSession(session.id)}
              okText="确定"
              cancelText="取消"
            >
              <Button
                type="text"
                size="small"
                icon={<DeleteOutlined />}
                className="text-zinc-400 hover:text-red-500"
                danger
              />
            </Popconfirm>
          </div>
        </div>
      </div>
    );
  };

  return (
    <div className="flex flex-col h-full px-3 pt-2">
      <div className="flex items-center gap-2">
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={handleCreateNewChat}
          className="flex-1"
        >
          新聊天
        </Button>
        <Button
          onClick={() => {
            setManageMode((prev) => !prev);
            setSelectedIds([]);
          }}
          disabled={chatSessions.length === 0}
        >
          {manageMode ? "完成" : "管理"}
        </Button>
      </div>

      {manageMode && (
        <div className="mt-3 rounded-lg border border-zinc-200 bg-zinc-50 px-3 py-2">
          <div className="flex items-center justify-between gap-2">
            <Checkbox
              indeterminate={indeterminate}
              checked={checkedAll}
              onChange={toggleAll}
            >
              已选 {selectedCount}
            </Checkbox>
            <Popconfirm
              title={`确定删除选中的 ${selectedCount} 条聊天记录吗？`}
              description="删除后将无法恢复"
              onConfirm={handleBatchDelete}
              okText="确定"
              cancelText="取消"
              disabled={selectedCount === 0}
            >
              <Button
                size="small"
                danger
                icon={<DeleteOutlined />}
                disabled={selectedCount === 0}
              >
                批量删除
              </Button>
            </Popconfirm>
          </div>
        </div>
      )}

      <div className="flex-1 min-h-0 overflow-y-auto mt-3">
        {loading ? (
          <div className="flex flex-col items-center justify-center h-full text-zinc-400">
            <p className="text-sm">加载中...</p>
          </div>
        ) : chatSessions.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-zinc-400">
            <MessageOutlined className="text-3xl mb-2 text-zinc-300" />
            <p className="text-sm">暂无聊天记录</p>
            <p className="text-xs mt-1">点击上方按钮创建新聊天</p>
          </div>
        ) : (
          <div className="space-y-5 pb-4 px-1">
            {pinnedSessions.length > 0 && (
              <section>
                <div className="sticky top-0 z-20 flex w-full items-center gap-2 bg-[var(--background)]/95 px-2 py-2 text-left backdrop-blur">
                  <PushpinFilled className="text-[12px] text-amber-500" />
                  <span className="min-w-0 flex-1 truncate text-xs font-semibold text-zinc-600">
                    已置顶
                  </span>
                  <span className="text-[11px] text-zinc-400">
                    {pinnedSessions.length}
                  </span>
                </div>
                <div className="space-y-0.5">
                  {pinnedSessions.map((session) =>
                    renderSessionItem(session, { showAgentDetails: true }),
                  )}
                </div>
              </section>
            )}

            {groupedSessions.map((group) => {
              const collapsed = collapsedGroups.has(group.key);

              return (
                <section key={group.key} className="rounded-xl">
                  <button
                    type="button"
                    onClick={() => toggleGroup(group.key)}
                    className="sticky top-0 z-10 flex w-full items-center gap-2 rounded-lg bg-[var(--background)]/95 px-2 py-2.5 text-left backdrop-blur transition-colors hover:bg-black/[.025]"
                  >
                    <span className="text-[10px] text-zinc-400">
                      {collapsed ? <CaretRightOutlined /> : <CaretDownOutlined />}
                    </span>
                    <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-[var(--brand-soft)]/65 text-sm leading-none">
                      {getAgentEmoji(group.agentId)}
                    </span>
                    <span className="min-w-0 flex-1 truncate text-[13px] font-semibold text-zinc-700">
                      {group.label}
                    </span>
                    <span className="text-[11px] text-zinc-400">
                      {group.sessions.length}
                    </span>
                  </button>

                  {!collapsed && (
                    <div className="mt-1 ml-5 space-y-1 border-l border-black/[.06] pl-2">
                      {group.sessions.map((session) =>
                        renderSessionItem(session, {
                          showAgentDetails: false,
                          separated: true,
                        }),
                      )}
                    </div>
                  )}
                </section>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export default ChatTabContent;
