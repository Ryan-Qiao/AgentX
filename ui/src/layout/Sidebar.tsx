import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <div
      className="h-full bg-[var(--background)] border-r border-[var(--border)] flex flex-col"
      style={{
        width: "348px",
      }}
    >
      {children}
    </div>
  );
};

export default Sidebar;
