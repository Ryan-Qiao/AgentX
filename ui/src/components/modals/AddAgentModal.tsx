import React, { useEffect, useState } from "react";
import { Button, Checkbox, Input, InputNumber, List, Modal, Select, Slider, Switch, message } from "antd";
import TextArea from "antd/es/input/TextArea";
import { DeleteOutlined, PlusOutlined, SaveOutlined } from "@ant-design/icons";
import {
  type CreateAgentRequest,
  type UpdateAgentRequest,
  type AgentVO,
  type ModelType,
  getOptionalTools,
  type ToolVO,
  type AgentMemoryVO,
  createAgentMemory,
  deleteAgentMemory,
  getAgentMemories,
  updateAgentMemory,
} from "../../api/api.ts";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";

interface AddAgentModalProps {
  open: boolean;
  onClose: () => void;
  createAgentHandle: (request: CreateAgentRequest) => Promise<void>;
  updateAgentHandle?: (
    agentId: string,
    request: UpdateAgentRequest,
  ) => Promise<void>;
  editingAgent?: AgentVO | null;
}

const menuItems = [
  { key: "base", label: "基础设置" },
  { key: "model", label: "模型设置" },
  { key: "knowledge", label: "知识库设置" },
  // { key: "mcp", label: "MCP 服务器" },
  { key: "tools", label: "工具调用" },
  { key: "memory", label: "记忆管理" },
];

const getTemperatureMax = (model: ModelType) => {
  return model === "glm-4.6" ? 1 : 2;
};

const AddAgentModal: React.FC<AddAgentModalProps> = ({
  open,
  onClose,
  createAgentHandle,
  updateAgentHandle,
  editingAgent,
}) => {
  // 菜单项
  const [selectedKey, setSelectedKey] = useState<string>("base");

  // 获取知识库列表
  const { knowledgeBases } = useKnowledgeBases();

  // 工具列表
  const [tools, setTools] = useState<ToolVO[]>([]);
  const [agentMemories, setAgentMemories] = useState<AgentMemoryVO[]>([]);
  const [memoryDraft, setMemoryDraft] = useState({
    title: "",
    content: "",
    memoryType: "fact",
    priority: 0,
  });

  // 表单数据
  const [formData, setFormData] = useState<CreateAgentRequest>({
    name: "智能体助手",
    description: "",
    systemPrompt: "你是一个很有用的智能体助手",
    model: "deepseek-chat",
    allowedTools: [],
    allowedKbs: [],
    chatOptions: {
      temperature: 0.7,
      topP: 1.0,
      messageLength: 20,
    },
    autoMemoryEnabled: false,
    autoMemoryInterval: 10,
  });

  const [createAgentLoading, setCreateAgentLoading] = useState(false);

  const refreshAgentMemories = async () => {
    if (!editingAgent?.id) {
      setAgentMemories([]);
      return;
    }
    const resp = await getAgentMemories(editingAgent.id);
    setAgentMemories(resp.agentMemories);
  };

  // 当编辑的 agent 变化时，更新表单数据
  useEffect(() => {
    if (editingAgent) {
      setFormData({
        name: editingAgent.name,
        description: editingAgent.description || "",
        systemPrompt: editingAgent.systemPrompt || "",
        model: editingAgent.model,
        allowedTools: editingAgent.allowedTools || [],
        allowedKbs: editingAgent.allowedKbs || [],
        chatOptions: editingAgent.chatOptions || {
          temperature: 0.7,
          topP: 1.0,
          messageLength: 10,
        },
        autoMemoryEnabled: editingAgent.autoMemoryEnabled ?? false,
        autoMemoryInterval: editingAgent.autoMemoryInterval ?? 10,
      });
    } else {
      // 重置表单
      setFormData({
        name: "agent",
        description: "",
        systemPrompt: "",
        model: "deepseek-chat",
        allowedTools: [],
        allowedKbs: [],
        chatOptions: {
          temperature: 0.7,
          topP: 1.0,
          messageLength: 10,
        },
        autoMemoryEnabled: false,
        autoMemoryInterval: 10,
      });
    }
  }, [editingAgent, open]);

  useEffect(() => {
    if (open && editingAgent?.id) {
      refreshAgentMemories().catch((error) => {
        console.error("获取 Agent 记忆失败:", error);
      });
    } else {
      setAgentMemories([]);
    }
  }, [open, editingAgent?.id]);

  useEffect(() => {
    const temperatureMax = getTemperatureMax(formData.model);
    const currentTemperature = formData.chatOptions?.temperature;
    if (
      currentTemperature !== undefined &&
      currentTemperature > temperatureMax
    ) {
      setFormData((prev) => ({
        ...prev,
        chatOptions: {
          ...prev.chatOptions,
          temperature: temperatureMax,
        },
      }));
    }
  }, [formData.model, formData.chatOptions?.temperature]);

  // 获取工具列表
  useEffect(() => {
    async function fetchTools() {
      try {
        const resp = await getOptionalTools();
        setTools(resp.tools);
      } catch (error) {
        console.error("获取工具列表失败:", error);
      }
    }

    fetchTools().then();
  }, []);

  const isEditMode = !!editingAgent;
  const temperatureMax = getTemperatureMax(formData.model);

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={isEditMode ? "编辑智能体" : "智能体助手"}
      footer={null}
      width={800}
      centered
    >
      <div className="flex h-[500px]">
        <div className="w-[150px] h-full border-r border-gray-200 pr-2">
          <div className="flex flex-col gap-0.5 select-none cursor-pointer">
            {menuItems.map((item) => {
              const isSelected = selectedKey === item.key;
              return (
                <React.Fragment key={item.key}>
                  <div
                    onClick={() => setSelectedKey(item.key)}
                    className={`px-3 py-2 rounded-lg hover:bg-gray-100 ${isSelected ? "bg-gray-100 text-gray-900 font-medium" : "text-gray-600"}`}
                  >
                    {item.label}
                  </div>
                </React.Fragment>
              );
            })}
          </div>
        </div>
        <div className="flex-1 h-full min-h-0 flex flex-col">
          <div className="flex-1 min-h-0 overflow-y-auto px-4 pb-4">
            {selectedKey === "base" && (
              <div>
                <div className="mb-3">
                  <label className="block text-gray-700 font-medium mb-1">
                    名称
                  </label>
                  <p className="text-xs text-gray-500 mb-2">
                    名称是这个 Agent 的基础身份。比如“老王”“Java 面试官”“产品顾问”，对话中它会以这个身份回应。
                  </p>
                  <div className="flex items-center">
                    <Input
                      placeholder="请输入 Agent 身份名称"
                      value={formData.name}
                      onChange={(e) =>
                        setFormData({ ...formData, name: e.target.value })
                      }
                    />
                  </div>
                </div>
                <div className="mb-3">
                  <label className="block text-gray-700 font-medium mb-1">
                    描述
                  </label>
                  <TextArea
                    placeholder="请输入智能体描述"
                    rows={2}
                    value={formData.description}
                    onChange={(e) =>
                      setFormData({ ...formData, description: e.target.value })
                    }
                  />
                </div>
                <div className="mb-3">
                  <label className="block text-gray-700 font-medium mb-1">
                    系统提示词
                  </label>
                  <p className="text-xs text-gray-500 mb-2">
                    系统提示词用于补充身份细节、背景信息、行为约束、输出风格和任务边界；不填写时仅使用上方名称作为基础身份。
                  </p>
                  <TextArea
                    placeholder="例如：你回答要简洁，不说脏话；你熟悉 Java 后端面试；用户正在准备校招。"
                    rows={11}
                    value={formData.systemPrompt}
                    onChange={(e) =>
                      setFormData({ ...formData, systemPrompt: e.target.value })
                    }
                  />
                </div>
                {!isEditMode && (
                  <div className="mb-3 rounded-lg border border-gray-200 p-3">
                    <div className="flex items-center justify-between gap-3">
                      <div>
                        <label className="block text-gray-700 font-medium mb-1">
                          Agent 自动记忆
                        </label>
                        <p className="text-xs text-gray-500">
                          开启后，Agent 会在后台定期整理对话，把长期有用的信息保存为当前 Agent 的记忆，用于后续会话。自动记忆只能在创建 Agent 时开启，创建后第一版不支持再打开或关闭。
                        </p>
                      </div>
                      <Switch
                        checked={!!formData.autoMemoryEnabled}
                        onChange={(checked) =>
                          setFormData({
                            ...formData,
                            autoMemoryEnabled: checked,
                            autoMemoryInterval: formData.autoMemoryInterval ?? 10,
                          })
                        }
                      />
                    </div>
                    {formData.autoMemoryEnabled && (
                      <div className="mt-3">
                        <label className="block text-sm text-gray-600 mb-1">
                          记忆整理频率
                        </label>
                        <div className="flex items-center gap-2">
                          <span className="text-sm text-gray-500">每</span>
                          <InputNumber
                            min={3}
                            max={50}
                            value={formData.autoMemoryInterval ?? 10}
                            onChange={(value) =>
                              setFormData({
                                ...formData,
                                autoMemoryInterval: value ?? 10,
                              })
                            }
                          />
                          <span className="text-sm text-gray-500">
                            条用户消息整理一次
                          </span>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
            {selectedKey === "model" && (
              <div>
                <div className="mb-4">
                  <label className="block text-gray-700 font-medium mb-1">
                    选择模型
                  </label>
                  <p className="text-xs text-gray-500 mb-2">
                    不同模型会影响回答质量、速度和费用。保存后，该智能体之后的新回复会使用这里选择的模型。
                  </p>
                  <Select
                    options={[
                      {
                        value: "deepseek-chat",
                        label: "deepseek-chat",
                      },
                      {
                        value: "glm-4.6",
                        label: "glm-4.6",
                      },
                    ]}
                    placeholder="请选择模型"
                    style={{ width: "300px" }}
                    value={formData.model}
                    onChange={(value: ModelType) =>
                      setFormData({
                        ...formData,
                        model: value,
                        chatOptions: {
                          ...formData.chatOptions,
                          temperature: Math.min(
                            formData.chatOptions?.temperature ?? 0.7,
                            getTemperatureMax(value),
                          ),
                        },
                      })
                    }
                  />
                </div>
                <div className="mb-4">
                  <label className="block text-gray-700 font-medium mb-2">
                    模型参数
                  </label>
                  <div className="mb-4 rounded-lg border border-blue-100 bg-blue-50 px-3 py-2 text-xs leading-5 text-blue-900">
                    这些参数会影响模型“怎么回答”，不会改变智能体的人设和工具权限。普通用户建议保持默认值。
                  </div>
                  <div className="space-y-4">
                    <div>
                      <div className="flex items-center justify-between mb-2">
                        <label className="block text-sm text-gray-600">
                          Temperature（温度）
                          <span className="text-gray-400 ml-1 text-xs">
                            (0.0 - {temperatureMax.toFixed(1)})
                          </span>
                        </label>
                        <span className="text-sm font-medium text-gray-700 min-w-[40px] text-right">
                          {formData?.chatOptions?.temperature?.toFixed(1)}
                        </span>
                      </div>
                      <Slider
                        min={0}
                        max={temperatureMax}
                        step={0.1}
                        value={formData?.chatOptions?.temperature}
                        onChange={(value) =>
                          setFormData({
                            ...formData,
                            chatOptions: {
                              ...formData.chatOptions,
                              temperature: value,
                            },
                          })
                        }
                      />
                      <p className="mt-1 text-xs leading-5 text-gray-500">
                        控制回答的发散程度。越低越稳定、越像标准答案；越高越有创意，但也更容易跑偏。写代码、查资料建议低一些，写文案可适当高一些。
                      </p>
                    </div>
                    <div>
                      <div className="flex items-center justify-between mb-2">
                        <label className="block text-sm text-gray-600">
                          Top P（核采样）
                          <span className="text-gray-400 ml-1 text-xs">
                            (0.0 - 1.0)
                          </span>
                        </label>
                        <span className="text-sm font-medium text-gray-700 min-w-[40px] text-right">
                          {formData?.chatOptions?.topP?.toFixed(1)}
                        </span>
                      </div>
                      <Slider
                        min={0}
                        max={1}
                        step={0.1}
                        value={formData?.chatOptions?.topP}
                        onChange={(value) =>
                          setFormData({
                            ...formData,
                            chatOptions: {
                              ...formData.chatOptions,
                              topP: value,
                            },
                          })
                        }
                      />
                      <p className="mt-1 text-xs leading-5 text-gray-500">
                        控制模型从多大范围里挑选下一个词。越低越保守，越高选择范围越大。通常不需要和温度同时大幅调整。
                      </p>
                    </div>
                    <div>
                      <div className="flex items-center justify-between mb-2">
                        <label className="block text-sm text-gray-600">
                          消息窗口长度
                          <span className="text-gray-400 ml-1 text-xs">
                            (2 - 100)
                          </span>
                        </label>
                        <span className="text-sm font-medium text-gray-700 min-w-[40px] text-right">
                          {formData?.chatOptions?.messageLength}
                        </span>
                      </div>
                      <Slider
                        min={2}
                        max={100}
                        step={1}
                        value={formData?.chatOptions?.messageLength}
                        onChange={(value) =>
                          setFormData({
                            ...formData,
                            chatOptions: {
                              ...formData.chatOptions,
                              messageLength: value,
                            },
                          })
                        }
                      />
                      <p className="mt-1 text-xs leading-5 text-gray-500">
                        控制每次回复前带入最近多少条聊天消息。越长越能记住当前会话上下文，但请求更大、响应可能更慢；这不是长期记忆，跨会话信息请使用 Agent Memory。
                      </p>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {selectedKey === "knowledge" && (
              <div>
                <div className="mb-4">
                  <label className="block text-gray-700 font-medium mb-3">
                    知识库
                  </label>
                  <p className="text-sm text-gray-500 mb-4">
                    选择智能体可以访问的知识库，支持多选（最多10个）
                  </p>
                  {knowledgeBases.length === 0 ? (
                    <div className="text-center py-8 text-gray-500">
                      <p>暂无知识库，请先创建知识库</p>
                    </div>
                  ) : (
                    <div className="space-y-3">
                      {knowledgeBases.map((kb) => {
                        const kbId = kb.knowledgeBaseId;
                        const isSelected = formData.allowedKbs?.includes(kbId);
                        return (
                          <div
                            key={kbId}
                            className={`border rounded-lg p-4 cursor-pointer transition-all hover:border-blue-400 hover:bg-blue-50 ${
                              isSelected
                                ? "border-blue-500 bg-blue-50"
                                : "border-gray-200"
                            }`}
                            onClick={() => {
                              const currentKbs = formData.allowedKbs || [];
                              if (isSelected) {
                                setFormData({
                                  ...formData,
                                  allowedKbs: currentKbs.filter(
                                    (k) => k !== kbId,
                                  ),
                                });
                              } else {
                                if (currentKbs.length >= 10) {
                                  return; // 最多选择10个
                                }
                                setFormData({
                                  ...formData,
                                  allowedKbs: [...currentKbs, kbId],
                                });
                              }
                            }}
                          >
                            <div className="flex items-start gap-2">
                              <Checkbox
                                checked={isSelected}
                                onChange={(e) => {
                                  e.stopPropagation();
                                  const currentKbs = formData.allowedKbs || [];
                                  if (e.target.checked) {
                                    if (currentKbs.length >= 10) {
                                      return; // 最多选择10个
                                    }
                                    setFormData({
                                      ...formData,
                                      allowedKbs: [...currentKbs, kbId],
                                    });
                                  } else {
                                    setFormData({
                                      ...formData,
                                      allowedKbs: currentKbs.filter(
                                        (k) => k !== kbId,
                                      ),
                                    });
                                  }
                                }}
                                className="mr-3"
                              />
                              <div className="flex-1">
                                <div className="flex items-center mb-1">
                                  <span className="font-medium text-gray-900">
                                    {kb.name}
                                  </span>
                                </div>
                                {kb.description && (
                                  <p className="text-sm text-gray-600">
                                    {kb.description}
                                  </p>
                                )}
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
                <div>
                  <label className="block text-gray-700 font-medium mb-1">
                    检索设置
                  </label>
                </div>
              </div>
            )}
            {selectedKey === "tools" && (
              <div>
                <div className="mb-4">
                  <label className="block text-gray-700 font-medium mb-3">
                    工具调用
                  </label>
                  <p className="text-sm text-gray-500 mb-4">
                    选择智能体可以使用的工具，支持多选
                  </p>
                  {tools.length === 0 ? (
                    <div className="text-center py-8 text-gray-500">
                      <p>暂无可用工具</p>
                    </div>
                  ) : (
                    <div className="space-y-3">
                      {tools.map((tool) => {
                        const toolId = tool.name;
                        const isSelected =
                          formData.allowedTools?.includes(toolId);
                        return (
                          <div
                            key={toolId}
                            className={`border rounded-lg p-4 cursor-pointer transition-all hover:border-blue-400 hover:bg-blue-50 ${
                              isSelected
                                ? "border-blue-500 bg-blue-50"
                                : "border-gray-200"
                            }`}
                            onClick={() => {
                              const currentTools = formData.allowedTools || [];
                              if (isSelected) {
                                setFormData({
                                  ...formData,
                                  allowedTools: currentTools.filter(
                                    (t) => t !== toolId,
                                  ),
                                });
                              } else {
                                setFormData({
                                  ...formData,
                                  allowedTools: [...currentTools, toolId],
                                });
                              }
                            }}
                          >
                            <div className="flex items-start gap-2">
                              <Checkbox
                                checked={isSelected}
                                onChange={(e) => {
                                  e.stopPropagation();
                                  const currentTools =
                                    formData.allowedTools || [];
                                  if (e.target.checked) {
                                    setFormData({
                                      ...formData,
                                      allowedTools: [...currentTools, toolId],
                                    });
                                  } else {
                                    setFormData({
                                      ...formData,
                                      allowedTools: currentTools.filter(
                                        (t) => t !== toolId,
                                      ),
                                    });
                                  }
                                }}
                                className="mr-3"
                              />
                              <div className="flex-1">
                                <div className="flex items-center mb-1">
                                  <span className="font-medium text-gray-900">
                                    {tool.name}
                                  </span>
                                </div>
                                <p className="text-sm text-gray-600">
                                  {tool.description}
                                </p>
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </div>
            )}
            {selectedKey === "memory" && (
              <div>
                {!isEditMode || !editingAgent ? (
                  <div className="text-sm text-gray-500 py-8 text-center">
                    请先保存 Agent，再管理该 Agent 的长期记忆。
                  </div>
                ) : (
                  <div>
                    <div className="mb-4">
                      <label className="block text-gray-700 font-medium mb-2">
                        Agent 长期记忆
                      </label>
                      <p className="text-sm text-gray-500 mb-4">
                        这些记忆只对当前 Agent 生效，会在该 Agent 的新会话中作为长期上下文注入。
                      </p>
                      <div className="border border-gray-200 rounded-lg p-3 mb-4">
                        <div className="grid grid-cols-2 gap-2 mb-2">
                          <Input
                            placeholder="标题，例如：用户备考方向"
                            value={memoryDraft.title}
                            onChange={(e) =>
                              setMemoryDraft({
                                ...memoryDraft,
                                title: e.target.value,
                              })
                            }
                          />
                          <Select
                            value={memoryDraft.memoryType}
                            onChange={(value) =>
                              setMemoryDraft({
                                ...memoryDraft,
                                memoryType: value,
                              })
                            }
                            options={[
                              { value: "fact", label: "事实" },
                              { value: "preference", label: "偏好" },
                              { value: "task", label: "任务" },
                              { value: "feedback", label: "反馈" },
                              { value: "decision", label: "决策" },
                            ]}
                          />
                        </div>
                        <TextArea
                          rows={3}
                          placeholder="记忆内容，例如：用户正在准备 Java 后端面试，希望回答带面试表达。"
                          value={memoryDraft.content}
                          onChange={(e) =>
                            setMemoryDraft({
                              ...memoryDraft,
                              content: e.target.value,
                            })
                          }
                        />
                        <div className="flex justify-end mt-2">
                          <Button
                            type="primary"
                            icon={<PlusOutlined />}
                            onClick={async () => {
                              if (!memoryDraft.title.trim() || !memoryDraft.content.trim()) {
                                message.warning("请填写记忆标题和内容");
                                return;
                              }
                              await createAgentMemory(editingAgent.id, {
                                title: memoryDraft.title.trim(),
                                content: memoryDraft.content.trim(),
                                memoryType: memoryDraft.memoryType,
                                priority: memoryDraft.priority,
                                enabled: true,
                              });
                              setMemoryDraft({
                                title: "",
                                content: "",
                                memoryType: "fact",
                                priority: 0,
                              });
                              await refreshAgentMemories();
                              message.success("Agent 记忆已保存");
                            }}
                          >
                            新增记忆
                          </Button>
                        </div>
                      </div>
                      <List
                        dataSource={agentMemories}
                        locale={{ emptyText: "暂无 Agent 记忆" }}
                        renderItem={(memoryItem) => (
                          <List.Item
                            actions={[
                              <Switch
                                key="enabled"
                                checked={memoryItem.enabled}
                                checkedChildren="启用"
                                unCheckedChildren="禁用"
                                onChange={async (checked) => {
                                  await updateAgentMemory(memoryItem.id, {
                                    enabled: checked,
                                  });
                                  await refreshAgentMemories();
                                }}
                              />,
                              <Button
                                key="delete"
                                danger
                                type="text"
                                icon={<DeleteOutlined />}
                                onClick={async () => {
                                  await deleteAgentMemory(memoryItem.id);
                                  await refreshAgentMemories();
                                  message.success("Agent 记忆已删除");
                                }}
                              />,
                            ]}
                          >
                            <List.Item.Meta
                              title={
                                <div className="flex items-center gap-2">
                                  <span>{memoryItem.title}</span>
                                  <span className="text-xs text-gray-400">
                                    {memoryItem.memoryType}
                                  </span>
                                </div>
                              }
                              description={
                                <div className="text-sm text-gray-600 whitespace-pre-wrap">
                                  {memoryItem.content}
                                </div>
                              }
                            />
                          </List.Item>
                        )}
                      />
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
          <div className="flex justify-end border-t border-gray-100 bg-white px-4 pt-3">
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={createAgentLoading}
              onClick={async () => {
                setCreateAgentLoading(true);
                try {
                  if (isEditMode && editingAgent && updateAgentHandle) {
                    const updatePayload: UpdateAgentRequest = {
                      name: formData.name,
                      description: formData.description,
                      systemPrompt: formData.systemPrompt,
                      model: formData.model,
                      allowedTools: formData.allowedTools,
                      allowedKbs: formData.allowedKbs,
                      chatOptions: formData.chatOptions,
                    };
                    await updateAgentHandle(editingAgent.id, updatePayload);
                  } else {
                    await createAgentHandle(formData);
                  }
                  onClose();
                } finally {
                  setCreateAgentLoading(false);
                }
              }}
            >
              {isEditMode ? "更新" : "保存"}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
};

export default AddAgentModal;
