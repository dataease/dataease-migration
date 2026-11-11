import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";
import "./account-plugins.css";
import "./workflows.css";
import "./lifecycle.css";
import "./instance-connection.css";
import "./interaction-layout.css";
import "./live.css";
createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
