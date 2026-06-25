import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <div
      className="h-full bg-white border-r border-zinc-200 flex flex-col"
      style={{
        width: "320px",
      }}
    >
      {children}
    </div>
  );
};

export default Sidebar;
