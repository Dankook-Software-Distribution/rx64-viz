import { defineConfig } from "vite";

// `npm run dev` serves the UI on :5173 and forwards API and console
// traffic to the server (docker run ... -p 127.0.0.1:8080:8080 rxviz).
export default defineConfig({
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
  build: {
    outDir: "dist",
    chunkSizeWarningLimit: 2000,
  },
});
