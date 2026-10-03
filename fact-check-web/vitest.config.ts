import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

/**
 * 前端单元测试配置（P4-47）
 *
 * 说明：
 * - 环境用 `node`：当前测试只覆盖**纯函数**（鉴权头映射、WebSocket 地址拼接），
 *   不需要 DOM。以后要测组件渲染时，把 `environment` 改成 `jsdom` 并安装
 *   `jsdom` + `@vue/test-utils` 即可。
 * - 这里故意不合并 `vite.config.ts`：那会连带启用 vue 插件与 dev server 代理，
 *   对纯逻辑单测是额外负担（也让"测试为什么需要起代理"变得费解）。
 * - 别名 `@` 与 vite.config.ts 保持一致，否则测试里的 `@/utils/...` 解析不到。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
    // 覆盖率需要 @vitest/coverage-v8，未安装时不要默认开启
    reporters: 'default'
  }
})
