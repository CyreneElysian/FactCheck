import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    // 拆包：把体积最大、且**版本变化最少**的两个依赖拆成独立 chunk
    //
    // 为什么值得做：不拆时主包 index-*.js 约 1.1 MB（gzip 约 370 kB），
    // 而它带内容 hash——只要**业务代码**改一行，整个 1.1 MB 都要重新下载。
    // 拆开后 vue 与 element-plus 各自成为独立 chunk，升级业务代码时浏览器
    // 直接命中缓存，首屏只需重下主包本身。
    //
    // 注意：这只是缓解"缓存粒度"问题，**不会**减小 element-plus 的总体积
    // （那需要按需引入 unplugin-vue-components，属于另一件事）。
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus', '@element-plus/icons-vue']
        }
      }
    },
    // 拆包后的实测体积（2026-10-04，本机 `npm run build`）：
    //   element-*.js  938.9 kB（gzip 301.8 kB）← 第三方库，按路由懒加载，首屏不必等它
    //   vue-*.js      110.9 kB（gzip  43.3 kB）
    //   index-*.js     67.2 kB（gzip  26.2 kB）← **业务主包**，这才是"改一行代码要重下的量"
    // 阈值设 1000 kB：element-plus 单库必然超过默认的 500 kB，留在那里只会每次构建都刷警告，
    // 反而让人忽略真正需要注意的"业务主包变大"。业务主包（index-*.js）应远低于此值。
    chunkSizeWarningLimit: 1000
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        ws: true
      }
    }
  }
})
