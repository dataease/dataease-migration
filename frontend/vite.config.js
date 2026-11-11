import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../target/generated-resources/operations/static",
    emptyOutDir: true,
  },
  server: {
    host: "127.0.0.1",
    port: 4281,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.OPERATIONS_API_URL || "http://127.0.0.1:8086",
        changeOrigin: false,
      },
    },
  },
});
