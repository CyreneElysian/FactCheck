/**
 * 零依赖 spec 运行器（离线兜底方案，P4-47）
 *
 * 用法：`npm run test:offline`
 *
 * 它做三件事：
 * 1. 注册解析钩子，把 spec 里的 `vitest` 与 `@/` 映射到本地实现/源码；
 * 2. 遍历 `src/**\/*.spec.ts` 并逐个 import（spec 内的断言在 import 期即执行）；
 * 3. 汇总 vitest-shim 收集到的结果，有失败则以非 0 退出。
 *
 * 注意：这是**兜底**，不是替代品——装好 vitest 后请用 `npm run test:run`（支持组件渲染、
 * 覆盖率、watch 等）。两者跑的是同一批 spec，不存在两份断言。
 */
import { readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const srcDir = path.join(projectRoot, 'src')

function collectSpecs(dir) {
  const found = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) {
      found.push(...collectSpecs(full))
    } else if (entry.endsWith('.spec.ts')) {
      found.push(full)
    }
  }
  return found
}

const specs = collectSpecs(srcDir)
if (specs.length === 0) {
  console.error('未找到任何 *.spec.ts')
  process.exit(1)
}

console.log(`零依赖模式运行 ${specs.length} 个 spec 文件：`)
for (const spec of specs) {
  console.log(`  - ${path.relative(projectRoot, spec)}`)
  await import(pathToFileURL(spec).href)
}

const { report } = await import('./vitest-shim.mjs')
const { passed, failures } = report()

console.log(`\n通过 ${passed} 条断言，失败 ${failures.length} 条`)
if (failures.length) {
  failures.forEach((f, index) => console.log(`\n  ${index + 1}. ${f}`))
  process.exit(1)
}
console.log('OK')
