import React, { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import {
  Button,
  Upload,
  Table,
  Popconfirm,
  Space,
  message,
  Empty,
  Typography,
} from "antd";
import {
  BookOutlined,
  UploadOutlined,
  DeleteOutlined,
  FileOutlined,
  DatabaseOutlined,
} from "@ant-design/icons";
import type { UploadProps } from "antd";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { useDocuments } from "../../hooks/useDocuments.ts";
import { uploadDocument, type DocumentVO } from "../../api/api.ts";

const { Text } = Typography;

const KnowledgeBaseView: React.FC = () => {
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId?: string }>();
  const { knowledgeBases } = useKnowledgeBases();
  const { documents, loading, refreshDocuments, deleteDocument } =
    useDocuments(knowledgeBaseId);

  const [uploading, setUploading] = useState(false);

  const currentKnowledgeBase = useMemo(() => {
    if (!knowledgeBaseId) return null;
    return (
      knowledgeBases.find((kb) => kb.knowledgeBaseId === knowledgeBaseId) ||
      null
    );
  }, [knowledgeBaseId, knowledgeBases]);

  const handleUpload: UploadProps["customRequest"] = async (options) => {
    const { file, onSuccess, onError } = options;

    if (!knowledgeBaseId) {
      message.error("请先选择知识库");
      return;
    }

    setUploading(true);

    try {
      await uploadDocument(knowledgeBaseId, file as File);
      message.success("文档上传成功");
      await refreshDocuments();
      onSuccess?.(file);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "上传失败");
      onError?.(error as Error);
    } finally {
      setUploading(false);
    }
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
  };

  const columns = [
    {
      title: "文件名",
      dataIndex: "filename",
      key: "filename",
      render: (text: string) => (
        <Space>
          <FileOutlined className="text-zinc-400" />
          <span className="text-sm">{text}</span>
        </Space>
      ),
    },
    {
      title: "类型",
      dataIndex: "filetype",
      key: "filetype",
      width: 120,
    },
    {
      title: "大小",
      dataIndex: "size",
      key: "size",
      width: 120,
      render: (size: number) => formatFileSize(size),
    },
    {
      title: "操作",
      key: "action",
      width: 100,
      render: (_: unknown, record: DocumentVO) => (
        <Popconfirm
          title="确定要删除这个文档吗？"
          description="删除后将无法恢复"
          onConfirm={() => deleteDocument(record.id)}
          okText="确定"
          cancelText="取消"
        >
          <Button type="text" size="small" danger icon={<DeleteOutlined />}>
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  if (!knowledgeBaseId) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          image={<DatabaseOutlined className="text-5xl text-zinc-300" />}
          description={
            <div className="mt-4 text-center">
              <p className="text-base font-medium text-zinc-600">未选择知识库</p>
              <Text type="secondary" className="text-sm">
                请从左侧知识库列表中选择一个知识库查看详情
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  if (!currentKnowledgeBase) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          description={
            <div className="mt-4 text-center">
              <p className="text-base font-medium text-zinc-600">知识库不存在</p>
              <Text type="secondary" className="text-sm">
                请检查知识库 ID 是否正确
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full p-6 overflow-y-auto bg-zinc-50">
      <div className="max-w-5xl w-full mx-auto space-y-4">
        {/* 知识库信息 */}
        <div className="bg-white rounded-xl border border-zinc-100 p-5">
          <div className="flex items-start gap-4">
            <div className="w-12 h-12 rounded-xl bg-indigo-50 flex items-center justify-center shrink-0">
              <BookOutlined className="text-xl text-indigo-500" />
            </div>
            <div className="flex-1 min-w-0">
              <h2 className="text-lg font-semibold text-zinc-900 mb-1">
                {currentKnowledgeBase.name}
              </h2>
              {currentKnowledgeBase.description && (
                <p className="text-sm text-zinc-500 mb-1">
                  {currentKnowledgeBase.description}
                </p>
              )}
              <p className="text-xs text-zinc-400">
                ID: {currentKnowledgeBase.knowledgeBaseId}
              </p>
            </div>
          </div>
        </div>

        {/* 上传文档 */}
        <div className="bg-white rounded-xl border border-zinc-100 p-5">
          <h3 className="text-sm font-semibold text-zinc-900 mb-3">上传文档</h3>
          <Upload
            customRequest={handleUpload}
            showUploadList={false}
            accept=".md"
            disabled={uploading}
          >
            <Button
              type="primary"
              icon={<UploadOutlined />}
              loading={uploading}
            >
              选择文件上传
            </Button>
          </Upload>
          <p className="text-xs text-zinc-400 mt-2">支持格式: Markdown</p>
        </div>

        {/* 文档列表 */}
        <div className="bg-white rounded-xl border border-zinc-100 p-5">
          <h3 className="text-sm font-semibold text-zinc-900 mb-3">
            文档列表 ({documents.length})
          </h3>
          {loading ? (
            <div className="text-center py-8 text-zinc-400">加载中...</div>
          ) : documents.length === 0 ? (
            <Empty
              image={null}
              description={<span className="text-zinc-400 text-sm">暂无文档，请上传文档</span>}
            />
          ) : (
            <Table
              columns={columns}
              dataSource={documents}
              rowKey="id"
              pagination={{
                pageSize: 10,
                showTotal: (total) => `共 ${total} 条`,
              }}
              size="small"
            />
          )}
        </div>
      </div>
    </div>
  );
};

export default KnowledgeBaseView;
