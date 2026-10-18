import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { resolve } from "node:path";

function offlineBundle() {
  let config;
  return {
    name: "nusasurvey-offline-bundle",
    configResolved(value) { config = value; },
    writeBundle(_, bundle) {
      const assets = Object.keys(bundle).filter(name => name !== "index.html").map(name => `/${name}`);
      const demoDirectory = resolve(config.root, "public/demo");
      if (existsSync(demoDirectory)) assets.push(...readdirSync(demoDirectory).filter(name => name.endsWith(".json")).map(name => `/demo/${name}`));
      const buildId = createHash("sha256").update(JSON.stringify(assets)).digest("hex").slice(0, 12);
      const source = readFileSync(resolve(config.root, "public/sw.js"), "utf8").replace("__BUILD_ID__", buildId).replace("/* __BUILD_ASSETS__ */", assets.map(x => JSON.stringify(x)).join(","));
      writeFileSync(resolve(config.root, config.build.outDir, "sw.js"), source);
    },
  };
}

const osmProxy = {
  "/osm-api": {
    target: "https://api.openstreetmap.org",
    changeOrigin: true,
    rewrite: (path) => path.replace(/^\/osm-api/, ""),
    headers: { "User-Agent": "NusaSurvey-SAR-Prototype/0.1" },
  },
};

export default defineConfig({
  plugins: [react(), offlineBundle()],
  server: { proxy: osmProxy },
  preview: { proxy: osmProxy },
});
