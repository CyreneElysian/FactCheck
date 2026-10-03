/**
 * 注册解析钩子（node 20+ 的现代写法，替代已废弃的 --experimental-loader）
 */
import { register } from 'node:module'
import { pathToFileURL } from 'node:url'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
register(pathToFileURL(path.join(here, 'spec-loader.mjs')))
