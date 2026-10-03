/**
 * 解析钩子：让 spec 文件在**零依赖**环境下也能被 node 直接执行
 *
 * 处理两类裸说明符：
 * 1. `vitest`  → 本目录的 vitest-shim.mjs（最小替身）
 * 2. `@/xxx`   → `./src/xxx`，并按 `.ts` / `.vue` 顺序补扩展名
 *    （node 的类型擦除要求显式扩展名，源码里写的是 Bundler 风格的无扩展导入）
 */
import { existsSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'vitest') {
    return {
      url: pathToFileURL(path.join(projectRoot, 'scripts', 'vitest-shim.mjs')).href,
      shortCircuit: true
    }
  }

  if (specifier.startsWith('@/')) {
    const base = path.join(projectRoot, 'src', specifier.slice(2))
    for (const candidate of [base, `${base}.ts`, `${base}.mjs`, `${base}.js`]) {
      if (existsSync(candidate)) {
        return { url: pathToFileURL(candidate).href, shortCircuit: true }
      }
    }
    throw new Error(`无法解析别名导入：${specifier}（在 ${projectRoot}/src 下找不到对应文件）`)
  }

  return nextResolve(specifier, context)
}
