import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  return {
    base: "/Smocnica/",
    plugins: [
      react(),
      {
        name: "worker-config",
        generateBundle() {
          if (!env.VITE_FIREBASE_CONFIG || !env.VITE_RECAPTCHA_SITE_KEY)
            throw new Error("Firebase web configuration is required.");
          this.emitFile({
            type: "asset",
            fileName: "firebase-config.js",
            source: `self.SMOCNICA_FIREBASE=${env.VITE_FIREBASE_CONFIG};`,
          });
        },
      },
    ],
    build: { target: "safari16.4" },
    server: { port: 5173, strictPort: true },
  };
});
