import React, { useMemo } from "react";
import { Button, Dropdown, Modal } from "antd";
import type { MenuProps } from "antd";
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  MoreOutlined,
} from "@ant-design/icons";
import type { AgentVO } from "../../api/api.ts";
import { formatDateTime, getAgentEmoji } from "../../utils";

interface AgentTabContentProps {
  agents: AgentVO[];
  onCreateAgentClick: () => void;
  onSelectAgent: (agentId: string) => void;
  onEditAgent?: (agent: AgentVO) => void;
  onDeleteAgent?: (agentId: string) => void;
}

const AgentTabContent: React.FC<AgentTabContentProps> = ({
  agents,
  onCreateAgentClick,
  onSelectAgent,
  onEditAgent,
  onDeleteAgent,
}) => {
  const agentsWithEmoji = useMemo(() => {
    return agents.map((agent) => ({
      ...agent,
      emoji: getAgentEmoji(agent.id),
    }));
  }, [agents]);

  const getContextMenuItems = (agent: AgentVO): MenuProps["items"] => {
    const items: MenuProps["items"] = [];

    if (onEditAgent) {
      items.push({
        key: "edit",
        label: "编辑",
        icon: <EditOutlined />,
        onClick: (e) => {
          e.domEvent.stopPropagation();
          onEditAgent(agent);
        },
      });
    }

    if (onDeleteAgent) {
      items.push({
        key: "delete",
        label: "删除",
        icon: <DeleteOutlined />,
        danger: true,
        onClick: (e) => {
          e.domEvent.stopPropagation();
          Modal.confirm({
            title: "确定要删除这个智能体吗？",
            content: "删除后将无法恢复",
            okText: "确定",
            cancelText: "取消",
            okType: "danger",
            onOk: () => {
              onDeleteAgent(agent.id);
            },
          });
        },
      });
    }

    return items;
  };

  return (
    <div className="flex flex-col h-full px-3 pt-2">
      <Button
        type="primary"
        icon={<PlusOutlined />}
        onClick={onCreateAgentClick}
        className="w-full"
      >
        智能体助手
      </Button>
      <div className="flex-1 overflow-y-auto mt-3">
        {agents.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-zinc-400">
            <p className="text-sm">暂无智能体</p>
            <p className="text-xs mt-1">点击上方按钮添加</p>
          </div>
        ) : (
          <div className="space-y-1">
            {agentsWithEmoji.map((agent) => {
              const menuItems = getContextMenuItems(agent);
              const hasMenu = menuItems && menuItems.length > 0;
              return (
                <div
                  key={agent.id}
                  onClick={() => onSelectAgent(agent.id)}
                  className="w-full px-3 py-2.5 rounded-lg cursor-pointer transition-all hover:bg-zinc-50 active:bg-zinc-100 group relative"
                >
                  <div className="flex items-start gap-2.5">
                    <div className="w-8 h-8 rounded-lg bg-indigo-50 flex items-center justify-center shrink-0 text-sm mt-0.5">
                      {agent.emoji}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="font-medium text-zinc-900 text-sm truncate">
                        {agent.name}
                      </div>
                      {agent.description && (
                        <div className="text-xs text-zinc-500 mt-0.5 line-clamp-1">
                          {agent.description}
                        </div>
                      )}
                      {agent.updatedAt && (
                        <div className="text-xs text-zinc-400 mt-0.5">
                          {formatDateTime(agent.updatedAt)}
                        </div>
                      )}
                    </div>
                    {hasMenu && (
                      <div
                        onClick={(e) => e.stopPropagation()}
                        onContextMenu={(e) => e.stopPropagation()}
                        className="opacity-0 group-hover:opacity-100 transition-opacity shrink-0 mt-0.5"
                      >
                        <Dropdown
                          menu={{ items: menuItems }}
                          trigger={["contextMenu", "click"]}
                          placement="bottomRight"
                        >
                          <Button
                            type="text"
                            size="small"
                            icon={<MoreOutlined />}
                            onClick={(e) => e.stopPropagation()}
                            className="text-zinc-400 hover:text-zinc-600"
                          />
                        </Dropdown>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export default AgentTabContent;
