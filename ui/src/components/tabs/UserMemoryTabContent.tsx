import { useCallback, useEffect, useState } from "react";
import { DeleteOutlined } from "@ant-design/icons";
import { Button, Empty, message, Popconfirm, Spin, Switch } from "antd";
import {
  deleteUserMemory,
  getUserMemories,
  updateUserMemory,
  type UserMemoryVO,
} from "../../api/api.ts";

const UserMemoryTabContent = () => {
  const [memories, setMemories] = useState<UserMemoryVO[]>([]);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const response = await getUserMemories();
      setMemories(response.userMemories ?? []);
    } catch (error) {
      console.error("获取用户级记忆失败:", error);
      message.error("获取用户级记忆失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return (
    <div className="flex h-full min-h-0 flex-col bg-white">
      <div className="border-b border-zinc-100 px-4 py-3">
        <div className="text-sm font-semibold text-zinc-900">用户级记忆</div>
        <div className="mt-1 text-xs leading-5 text-zinc-400">
          由所有 Agent 共享，适合保存长期稳定的背景、偏好和沟通习惯。
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-2">
        {loading ? (
          <div className="flex h-full items-center justify-center"><Spin /></div>
        ) : memories.length === 0 ? (
          <div className="flex h-full items-center justify-center px-4">
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无用户级记忆" />
          </div>
        ) : (
          <div className="space-y-1">
            {memories.map((memory) => (
              <div key={memory.id} className="rounded-lg px-3 py-3 transition-colors hover:bg-zinc-50">
                <div className="flex items-start gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate text-sm font-medium text-zinc-800">{memory.title}</span>
                      <span className="shrink-0 text-[11px] text-zinc-400">{memory.memoryType}</span>
                    </div>
                    <div className="mt-1 whitespace-pre-wrap text-xs leading-5 text-zinc-500">{memory.content}</div>
                  </div>
                </div>
                <div className="mt-2 flex items-center justify-end gap-2">
                  <Switch
                    size="small"
                    checked={memory.enabled}
                    checkedChildren="启用"
                    unCheckedChildren="禁用"
                    onChange={async (checked) => {
                      await updateUserMemory(memory.id, { enabled: checked });
                      await refresh();
                    }}
                  />
                  <Popconfirm
                    title="删除这条用户级记忆？"
                    description="删除后所有 Agent 都不会再注入这条记忆。"
                    okText="删除"
                    cancelText="取消"
                    okButtonProps={{ danger: true }}
                    onConfirm={async () => {
                      await deleteUserMemory(memory.id);
                      await refresh();
                      message.success("用户级记忆已删除");
                    }}
                  >
                    <Button size="small" danger type="text" aria-label="删除用户级记忆" icon={<DeleteOutlined />} />
                  </Popconfirm>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default UserMemoryTabContent;
