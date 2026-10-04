/**
 * 构建包装脚本：把**临时目录**指到项目内的 .tmp/，再启动 vite。
 *
 * 为什么需要它（Windows 实测问题，不是配置或代码缺陷）：
 *   `npm run build` 在 `vite:esbuild-transpile` 阶段失败：
 *       [vite:esbuild-transpile] remove C:\Users\...\Temp\esbuild-<hash>: Access is denied.
 *   注意此时 **1733 个模块已经全部转换成功** —— 失败的是 esbuild 删除自己的临时文件。
 *   常见原因：
 *     1. 安全软件 / 零信任客户端（如深信服 aTrust）在句柄未释放时扫描并锁住该文件；
 *     2. OneDrive 等同步盘正在同步 %TEMP% 下的文件；
 *     3. %TEMP% 目录 ACL 被收紧。
 *   把临时目录换到项目内的 .tmp/ 后，这些拦截通常不再命中。
 *
 * 为什么不用 .npmrc 的 `tmp` 键：npm **不支持**该配置（会打印
 * `Unknown project config "tmp"`），设了也不生效。esbuild 读的是进程的
 * TMP/TEMP 环境变量，所以必须在**启动 vite 之前**改好环境变量。
 *
 * 用法：`npm run build`（package.json 里已指向本脚本）。
 * 单独跑 vite 时也生效：`node scripts/build.mjs`
 */
import { spawn } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const projectRoot = join(here, '..')
const tmpDir = join(projectRoot, '.tmp')

mkdirSync(tmpDir, { recursive: true })

// vite 参数：
//   node scripts/build.mjs --dev        → vite（开发服务器）
//   node scripts/build.mjs              → vite build（生产构建，默认）
//   node scripts/build.mjs --mode test  → vite build --mode test
//   node scripts/build.mjs --print      → 只打印将要执行的命令（不透传给 vite）
const rawArgs = process.argv.slice(2)
const wantPrint = rawArgs.includes('--print')
const devMode = rawArgs.includes('--dev')
// 先剔掉本脚本自己的开关，避免被当成 vite 的参数（踩过：--print 被透传成 `vite --print`）
const passthrough = rawArgs.filter((a) => a !== '--dev' && a !== '--print')
const viteArgs = devMode ? passthrough : (passthrough.length > 0 ? passthrough : ['build'])

const isWindows = process.platform === 'win32'

// 如何启动 vite —— 这里踩过两个坑，最终用"拼成单条命令字符串"的写法：
//   1. spawn('npx.cmd', [...], { shell: true }) → Node 18+ 报 **DEP0190**
//      （"Passing args to a child process with shell option true ..."）；
//   2. spawn('npx.cmd', [...])（shell:false）→ Windows 上 **EINVAL**，
//      Node 不能直接 spawn 批处理（.cmd/.bat）。
//   3. 正确写法：把可执行文件与参数拼成**一条命令字符串**，配 shell:true、且**不传 args 数组**
//      —— 各参数都会经过引号处理，不会触发 DEP0190，也不会有转义/空格问题。
function quote(arg) {
  return /[\s"&|<>^]/.test(arg) ? `"${arg.replace(/"/g, '\\"')}"` : arg
}

const viteBin = isWindows ? 'npx.cmd' : 'npx'
const commandLine = [viteBin, 'vite', ...viteArgs].map(quote).join(' ')

// --print：只打印将要执行的命令与生效的临时目录，不真正构建。
// 用途：在构建出问题时快速确认"包装脚本到底把 TMP 指到哪了"，
// 以及在无法执行子进程的环境里做干跑自检。
if (wantPrint) {
  console.log('[build] platform     :', process.platform)
  console.log('[build] cwd          :', projectRoot)
  console.log('[build] TMP/TEMP     :', tmpDir)
  console.log('[build] command      :', commandLine)
  process.exit(0)
}

const child = spawn(commandLine, {
  cwd: projectRoot,
  stdio: 'inherit',
  shell: true,
  env: {
    ...process.env,
    TMP: tmpDir,
    TEMP: tmpDir,
    // 顺带把 esbuild 自己的下划线变量也指过去，避免某些平台读到旧值
    TMPDIR: tmpDir
  }
})

child.on('exit', (code, signal) => {
  if (signal) {
    console.error(`[build] vite 被信号 ${signal} 终止`)
    process.exit(1)
  }
  process.exit(code ?? 1)
})

child.on('error', (err) => {
  console.error('[build] 启动 vite 失败：', err.message)
  process.exit(1)
})
