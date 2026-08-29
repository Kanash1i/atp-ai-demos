import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// 后端地址只从环境变量来（仓库红线：代码里不得出现硬编码的 key、URL、IP）。
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
        '/api': {
          target,
          changeOrigin: true,
          configure: (proxy) => {
            /*
             * 把 Origin 头摘掉。
             *
             * 浏览器对**非 GET/HEAD** 的请求，即使是同源也会带 Origin（Fetch 规范如此）。
             * 于是 POST 到 /api 时后端看到的是 Origin: http://localhost:5273，
             * 而 changeOrigin 已经把 Host 改成了 8080 —— 两边对不上，
             * Spring 判定为跨域请求，走 CORS 检查，5273 不在白名单里就 403「Invalid CORS request」。
             *
             * 症状很有迷惑性：所有读接口都正常（同源 GET 不带 Origin），
             * 只有派发执行和审批决策这两个写操作 403。
             *
             * 代理的意义就是让后端把请求当同源看待，那就不该留一个指向 dev server 的 Origin。
             * 摘掉之后后端根本不进 CORS 分支。
             * 生产是前后端同域，浏览器带的 Origin 与 Host 一致，Spring 判定为同源，也不进这个分支。
             */
            proxy.on('proxyReq', (proxyReq) => {
              proxyReq.removeHeader('origin');
            });
          },
        },
      },
    },
  };
});
