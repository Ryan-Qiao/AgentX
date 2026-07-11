import React, { useState } from "react";
import { Sender } from "@ant-design/x";

interface AgentChatInputProps {
  onSend: (message: string) => void;
}

const AgentChatInput: React.FC<AgentChatInputProps> = ({ onSend }) => {
  const [message, setMessage] = useState("");

  return (
    <div className="composer-shell mx-auto max-w-[820px]">
    <Sender
      className="quiet-sender"
      onSubmit={() => {
        onSend(message.trim());
        setMessage("");
      }}
      placeholder="问点什么，或交给 Agent 去完成…"
      value={message}
      onChange={setMessage}
    />
    <div className="px-4 pb-2 text-[11px] text-zinc-400">Enter 发送 · Shift + Enter 换行</div>
    </div>
  );
};

export default AgentChatInput;
