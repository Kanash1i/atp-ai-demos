import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// 后端地址只从环境变量来（仓库红线：代码里不得出现硬编码的 key / URL / IP）。
// 本地默认 http://localhost:8080，改后端机器时写 .env.local 的 VITE_API_ORIGIN。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.VITE_API_ORIGIN || 'http://localhost:8080';

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5273,
      proxy: {
        // 同源走代理，避免 CORS，也让生产部署时前后端同域的形状保持一致
        '/api': { target, changeOrigin: true },
      },
    },
  };
});
