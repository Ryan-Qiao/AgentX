import React, { useMemo } from "react";
import { Button } from "antd";
import { PlusOutlined, BookOutlined } from "@ant-design/icons";
import type { KnowledgeBase } from "../../types";
import { getKnowledgeBaseEmoji } from "../../utils";

interface KnowledgeBaseTabContentProps {
  knowledgeBases: KnowledgeBase[];
  onCreateKnowledgeBaseClick?: () => void;
  onSelectKnowledgeBase?: (knowledgeBaseId: string) => void;
}

const KnowledgeBaseTabContent: React.FC<KnowledgeBaseTabContentProps> = ({
  knowledgeBases,
  onCreateKnowledgeBaseClick,
  onSelectKnowledgeBase,
}) => {
  const knowledgeBasesWithEmoji = useMemo(() => {
    return knowledgeBases.map((kb) => ({
      ...kb,
      emoji: getKnowledgeBaseEmoji(kb.knowledgeBaseId),
    }));
  }, [knowledgeBases]);

  return (
    <div className="flex flex-col h-full px-3 pt-2">
      <Button
        type="primary"
        icon={<PlusOutlined />}
        onClick={onCreateKnowledgeBaseClick}
        className="w-full"
      >
        新建知识库
      </Button>
      <div className="flex-1 overflow-y-scroll mt-3">
        {knowledgeBases.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-zinc-400">
            <BookOutlined className="text-3xl mb-2 text-zinc-300" />
            <p className="text-sm">暂无知识库</p>
            <p className="text-xs mt-1">点击上方按钮创建</p>
          </div>
        ) : (
          <div className="space-y-0.5">
            {knowledgeBasesWithEmoji.map((kb) => (
              <div
                key={kb.knowledgeBaseId}
                onClick={() => onSelectKnowledgeBase?.(kb.knowledgeBaseId)}
                className="w-full px-3 py-2.5 rounded-lg cursor-pointer transition-all hover:bg-zinc-50 active:bg-zinc-100"
              >
                <div className="flex items-start gap-2.5">
                  <div className="w-8 h-8 rounded-lg bg-indigo-50 flex items-center justify-center shrink-0 text-sm mt-0.5">
                    {kb.emoji}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-zinc-900 text-sm truncate">
                      {kb.name}
                    </div>
                    {kb.description && (
                      <div className="text-xs text-zinc-500 mt-0.5 line-clamp-2">
                        {kb.description}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default KnowledgeBaseTabContent;
