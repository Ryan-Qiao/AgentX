import { createRoot } from "react-dom/client";
import { ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import App from "./App.tsx";
import "./index.css";

const theme = {
  token: {
    colorPrimary: "#6366F1",
    borderRadius: 8,
    colorBgContainer: "#ffffff",
    colorBorderSecondary: "#e4e4e7",
    colorBgElevated: "#ffffff",
    fontSize: 14,
  },
};

createRoot(document.getElementById("root")!).render(
  <ConfigProvider locale={zhCN} theme={theme}>
    <App />
  </ConfigProvider>
);
