import { useState } from "react";
import {
  Button,
  Card,
  Collapse,
  Descriptions,
  Empty,
  Input,
  Spin,
  Tag,
  Timeline,
  Typography,
} from "antd";
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import {
  getAgentTrace,
  type AgentTraceDetailResponse,
  type AgentTraceEventDetail,
} from "../../api/api";

const { Text, Title } = Typography;

function statusColor(status: string) {
  if (status === "COMPLETED") return "success";
  if (status === "FAILED") return "error";
  return "processing";
}

function eventIcon(event: AgentTraceEventDetail) {
  if (event.status === "FAILED") return <CloseCircleOutlined className="text-red-500" />;
  if (event.status === "COMPLETED") return <CheckCircleOutlined className="text-emerald-500" />;
  return <ClockCircleOutlined className="text-indigo-500" />;
}

function JsonBlock({ value }: { value?: Record<string, unknown> }) {
  if (!value || Object.keys(value).length === 0) return null;
  return (
    <pre className="max-h-80 overflow-auto rounded-lg bg-zinc-950 p-4 text-xs leading-5 text-zinc-100">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

export default function AgentTraceView() {
  const [traceId, setTraceId] = useState("");
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<AgentTraceDetailResponse | null>(null);
  const [error, setError] = useState("");

  const query = async () => {
    const id = traceId.trim();
    if (!id) return;
    setLoading(true);
    setError("");
    try {
      setDetail(await getAgentTrace(id));
    } catch (e) {
      setDetail(null);
      setError(e instanceof Error ? e.message : "Trace 查询失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="h-full overflow-auto bg-zinc-50 p-8">
      <div className="mx-auto max-w-6xl space-y-6">
        <div>
          <Title level={2} className="!mb-1">Agent Trace</Title>
          <Text type="secondary">输入 Trace ID，回放一次 Agent 的模型调用、工具执行和步骤状态。</Text>
        </div>

        <Card>
          <div className="flex gap-3">
            <Input
              size="large"
              value={traceId}
              onChange={(event) => setTraceId(event.target.value)}
              onPressEnter={() => void query()}
              placeholder="例如：550e8400-e29b-41d4-a716-446655440000"
              prefix={<SearchOutlined className="text-zinc-400" />}
              allowClear
            />
            <Button size="large" type="primary" loading={loading} onClick={() => void query()}>
              查询 Trace
            </Button>
          </div>
          {error && <Text type="danger" className="mt-3 block">{error}</Text>}
        </Card>

        {loading ? (
          <div className="flex justify-center py-24"><Spin size="large" /></div>
        ) : detail ? (
          <>
            <Card title="运行摘要">
              <Descriptions column={{ xs: 1, sm: 2, lg: 3 }}>
                <Descriptions.Item label="Trace ID"><Text copyable>{detail.trace.id}</Text></Descriptions.Item>
                <Descriptions.Item label="状态"><Tag color={statusColor(detail.trace.status)}>{detail.trace.status}</Tag></Descriptions.Item>
                <Descriptions.Item label="结束原因">{detail.trace.finishReason || "-"}</Descriptions.Item>
                <Descriptions.Item label="模型">{detail.trace.modelName || "-"}</Descriptions.Item>
                <Descriptions.Item label="总耗时">{detail.trace.durationMs == null ? "-" : `${detail.trace.durationMs} ms`}</Descriptions.Item>
                <Descriptions.Item label="完整性">
                  <Tag color={detail.trace.traceIncomplete ? "warning" : "success"}>
                    {detail.trace.traceIncomplete ? "事件可能缺失" : "完整"}
                  </Tag>
                </Descriptions.Item>
                <Descriptions.Item label="步骤">{detail.trace.totalSteps}</Descriptions.Item>
                <Descriptions.Item label="模型调用">{detail.trace.totalModelCalls}</Descriptions.Item>
                <Descriptions.Item label="工具调用">{detail.trace.totalToolCalls}</Descriptions.Item>
              </Descriptions>
            </Card>

            <Card title={`行为时间线（${detail.events.length} 个事件）`}>
              <Timeline
                items={detail.events.map((event) => ({
                  dot: eventIcon(event),
                  children: (
                    <div className="pb-3">
                      <div className="mb-2 flex flex-wrap items-center gap-2">
                        <Text strong>#{event.sequenceNo} {event.eventType}</Text>
                        {event.stepNo != null && <Tag>Step {event.stepNo}</Tag>}
                        {event.eventName && <Text type="secondary">{event.eventName}</Text>}
                        {event.durationMs != null && <Text type="secondary">{event.durationMs} ms</Text>}
                      </div>
                      {(event.payload || event.error || event.metadata) && (
                        <Collapse
                          size="small"
                          items={[{
                            key: event.eventId,
                            label: "查看事件详情",
                            children: (
                              <div className="space-y-3">
                                {event.payload && <><Text strong>Payload</Text><JsonBlock value={event.payload} /></>}
                                {event.error && <><Text strong>Error</Text><JsonBlock value={event.error} /></>}
                                {event.metadata && <><Text strong>Metadata</Text><JsonBlock value={event.metadata} /></>}
                              </div>
                            ),
                          }]}
                        />
                      )}
                    </div>
                  ),
                }))}
              />
            </Card>
          </>
        ) : (
          <Card><Empty description="输入 Trace ID 后查询 Agent 行为" /></Card>
        )}
      </div>
    </div>
  );
}
