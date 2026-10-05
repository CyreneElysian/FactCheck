/**
 * 小程序「登录守卫 + 角色分支」的回归测试（离线、确定性）
 *
 * ## 一、登录竞态（要钉住的真实 bug）
 *
 * 冷启动时 `app.onLaunch` 的静默登录需要一次网络往返（约 200~500ms），
 * 而 tab 页的 `onShow` 会**立即**发请求。若该页没有 `await loginReady`：
 *
 *     app.onLaunch: silentLogin() ──[网络往返]──► saveLogin() 写入 token
 *                                 ↘
 *     页面 onShow: get('/detect/records') ──► getStorageSync('token') = ''
 *                                 ↘ 请求不带鉴权头
 *                                   后端 401（jjwt: CharSequence cannot be null or empty）
 *                                   → request.js 清 token + 跳登录页
 *
 * 用户看到的现象就是"登录之后点底部功能栏，又跳回登录页"。
 *
 * ## 二、角色分支
 *
 * 小程序端原先是"所有角色同一套界面"。现在「我的」页按 `role` 分岔：
 * 管理员看到管理端入口（待诊断队列 / 统计概览），普通用户看到用户功能。
 * 本文件同时验证这件事**真的按 role 渲染**，而不只是验证工具函数。
 *
 * ## 断言一览（30 项）
 *
 * | # | 断言 |
 * | --- | --- |
 * | 1 | 不带守卫的页面在冷启动竞态下确实发出**无鉴权头**的请求 —— 复现 bug |
 * | 2 | 带守卫的页面会等 `loginReady`，请求带上正确鉴权头 —— 修复有效 |
 * | 3 | `loginReady` 未落定时的 401 **不**清 token、**不**跳转（不抹掉即将生效的登录态）|
 * | 4 | `loginReady` 已落定后的 401 → 清残留并跳登录页（token 过期时必须能回到登录页）|
 * | 5 | 守卫在无 token 时返回 false 并用 `reLaunch` 跳登录页（不压栈）|
 * | 6 | 「我的」页按 role 渲染不同菜单；`isAdmin()` 容忍 role 存成字符串或数字 |
 * | 7 | `ensureAdmin()`：管理员放行 / 普通用户送回首页 / 未登录去登录页 |
 *
 * 用法：node scripts/test-auth-race.js
 */
const path = require('path')
const Module = require('module')

const MINI = path.join(__dirname, '..')

let storage
let requests
let navigations
let wx

function resetWorld() {
  storage = new Map()
  requests = []
  navigations = []

  wx = {
    setStorageSync: (k, v) => storage.set(k, v),
    getStorageSync: k => (storage.has(k) ? storage.get(k) : ''),
    removeStorageSync: k => storage.delete(k),
    login: ({ success }) => success({ code: 'FAKE_CODE' }),
    showToast: () => {},
    request: opts => {
      requests.push({ url: opts.url, header: opts.header })
      const resp = global.__nextResponse || { statusCode: 200, data: { code: 1, msg: 'ok', data: {} } }
      setImmediate(() => opts.success(resp))
    },
    reLaunch: o => navigations.push({ type: 'reLaunch', url: o.url })
  }
  global.wx = wx
  global.getCurrentPages = () => [{ route: 'pages/hot/hot' }]
  global.__nextResponse = undefined
}

function freshRequire(rel) {
  const p = require.resolve(path.join(MINI, rel))
  delete require.cache[p]
  return require(p)
}

/**
 * 模拟 `app.onLaunch` 的收尾：设置 `globalData.loginReady` 并开始追踪它是否已落定。
 *
 * @param {{promise: Promise}|null} deferred 传对象表示"登录还在路上"；传 null 表示"已经结束"
 */
function simulateOnLaunch(deferred) {
  const loginReady = deferred ? deferred.promise : Promise.resolve({ skipped: true })
  global.getApp = () => ({ globalData: { userInfo: null, loginReady } })
  freshRequire('utils/request.js').trackLoginReady()
  return deferred
}

/** 造一个可控的 loginReady */
function makeDeferred() {
  const d = {}
  d.promise = new Promise((resolve, reject) => {
    d.resolve = resolve
    d.reject = reject
  })
  return d
}

function authHeaderOf(req) {
  const h = (req && req.header) || {}
  if (h.authentication) return { kind: 'authentication', value: h.authentication }
  if (h.token) return { kind: 'token', value: h.token }
  return null
}

/**
 * 加载一个页面模块并拿到它的配置对象（页面逻辑里调用的 `Page({...})`）。
 *
 * 这样可以在 Node 里直接调用页面的 `onShow` / `onLoad`，验证**角色分支**
 * 真的按 role 渲染，而不是只验证 auth-guard 这个工具函数。
 */
function loadPage(rel) {
  let captured = null
  const prevPage = global.Page
  global.Page = cfg => { captured = cfg }
  try {
    freshRequire(rel)
  } finally {
    global.Page = prevPage
  }
  if (!captured) {
    throw new Error('页面模块没有调用 Page()：' + rel)
  }
  // 给页面一个可用的 setData（真实小程序里由框架注入）
  captured.setData = function (patch) {
    Object.assign(this.data, patch)
  }
  captured.data = Object.assign({}, captured.data)
  return captured
}

const results = []
function check(name, cond, detail) {
  results.push({ name, ok: !!cond })
  console.log(`  ${cond ? '✅' : '❌'} ${name}${detail ? '  —— ' + detail : ''}`)
}

const sleep = ms => new Promise(r => setTimeout(r, ms))
function header(t) {
  console.log()
  console.log('='.repeat(74))
  console.log('  ' + t)
  console.log('='.repeat(74))
}

async function main() {
  const origLoad = Module._load
  Module._load = function (request) {
    if (request === 'wx') return global.wx
    return origLoad.apply(this, arguments)
  }

  // ---- 断言 1：复现 bug ----
  header('断言 1：无守卫的页面在竞态下会发出无鉴权头的请求（复现 bug）')
  resetWorld()
  const d1 = simulateOnLaunch(makeDeferred())
  global.__nextResponse = { statusCode: 401, data: {} }
  const rst = freshRequire('utils/request.js')
  const req = rst.get('/detect/records').catch(() => {})
  await sleep(10)
  check('竞态下请求不带鉴权头', authHeaderOf(requests[0]) === null,
    `header=${JSON.stringify(requests[0] && requests[0].header)}`)
  check('loginReady 未落定时不跳转（不清掉即将生效的登录态）',
    navigations.length === 0, JSON.stringify(navigations))
  d1.resolve({ needBind: true })
  await req
  await sleep(600)
  check('登录流程落定且仍无 token → 跳登录页',
    navigations.some(n => n.url === '/pages/login/login'), JSON.stringify(navigations))

  // ---- 断言 2：修复有效 ----
  header('断言 2：带守卫的页面会等 loginReady，请求带上正确鉴权头')
  resetWorld()
  const d2 = simulateOnLaunch(makeDeferred())
  const guard = freshRequire('utils/auth-guard.js')
  const rst2 = freshRequire('utils/request.js')
  const pageFlow = (async () => {
    if (!(await guard.ensureLogin())) return 'redirected'
    await rst2.get('/detect/records')
    return 'loaded'
  })()
  await sleep(20)
  check('守卫阻塞，loginReady 落定前不发请求', requests.length === 0, `已发 ${requests.length} 条`)
  storage.set('token', 'TOKEN_AFTER_SILENT_LOGIN')
  storage.set('role', '0')
  global.__nextResponse = { statusCode: 200, data: { code: 1, msg: 'ok', data: {} } }
  d2.resolve({ success: true })
  const outcome = await pageFlow
  check('守卫放行后页面正常加载', outcome === 'loaded', `outcome=${outcome}`)
  const h2 = authHeaderOf(requests[0])
  check('请求带上正确鉴权头', !!h2 && h2.value === 'TOKEN_AFTER_SILENT_LOGIN',
    JSON.stringify(h2))
  check('没有被误踢到登录页', navigations.length === 0, JSON.stringify(navigations))

  // ---- 断言 3：竞态型 401 不清 token ----
  header('断言 3：loginReady 未落定时的 401 不清 token、不跳转')
  resetWorld()
  const d3 = simulateOnLaunch(makeDeferred())
  storage.set('token', 'STALE_TOKEN')
  const rst3 = freshRequire('utils/request.js')
  global.__nextResponse = { statusCode: 401, data: {} }
  await rst3.get('/detect/records').catch(() => {})
  await sleep(600)
  check('token 未被清掉', storage.get('token') === 'STALE_TOKEN', `token=${storage.get('token')}`)
  check('未跳转', navigations.length === 0, JSON.stringify(navigations))
  d3.resolve({ skipped: true })

  // ---- 断言 4：已落定的 401 必须清并跳 ----
  header('断言 4：loginReady 已落定后的 401 → 清残留并跳登录页')
  resetWorld()
  simulateOnLaunch(null)                      // 登录流程已结束
  storage.set('token', 'EXPIRED_TOKEN')       // token 已失效
  const rst4 = freshRequire('utils/request.js')
  await sleep(0)                              // 让 trackLoginReady 的 then 先跑（标记已落定）
  global.__nextResponse = { statusCode: 401, data: {} }
  await rst4.get('/detect/records').catch(() => {})
  await sleep(600)
  check('已落定的 401 会清掉失效 token', !storage.has('token'), `token=${storage.get('token')}`)
  check('并跳转到登录页（reLaunch）',
    navigations.some(n => n.type === 'reLaunch' && n.url === '/pages/login/login'),
    JSON.stringify(navigations))

  // ---- 断言 5：守卫无 token 时的行为 ----
  header('断言 5：守卫在无 token 时返回 false 并用 reLaunch 跳登录页')
  resetWorld()
  simulateOnLaunch(null)
  const guard5 = freshRequire('utils/auth-guard.js')
  await sleep(0)
  const allowed = await guard5.ensureLogin()
  check('ensureLogin() 返回 false', allowed === false, `=${allowed}`)
  check('用 reLaunch（不把登录页压栈）',
    navigations.some(n => n.type === 'reLaunch' && n.url === '/pages/login/login'),
    JSON.stringify(navigations))

  // ---- 断言 6：按角色渲染 ----
  header('断言 6：「我的」页按 role 渲染不同的菜单')
  resetWorld()
  simulateOnLaunch(null)
  await sleep(0)
  const guard6 = freshRequire('utils/auth-guard.js')

  // isAdmin 必须容忍 role 被写成字符串或数字（写数字 1 时 `=== 1` 会判错，
  // request.js 的鉴权头踩过同一个坑）
  storage.set('role', '1')
  check("isAdmin() 认字符串 '1'", guard6.isAdmin() === true)
  storage.set('role', 1)
  check('isAdmin() 认数字 1', guard6.isAdmin() === true)
  storage.set('role', '0')
  check("isAdmin() 不认 '0'", guard6.isAdmin() === false)
  storage.set('role', '')
  check('isAdmin() 不认空串', guard6.isAdmin() === false)

  // 管理员：菜单里应出现管理端入口
  storage.set('token', 'ADMIN_TOKEN')
  storage.set('role', '1')
  storage.set('userInfo', { id: 1, username: 'admin', nickname: '管理员', role: 1 })
  global.__nextResponse = { statusCode: 200, data: { code: 1, msg: 'ok', data: 0 } }
  freshRequire('utils/request.js')
  const minePage = loadPage('pages/mine/mine.js')
  await minePage.onShow()
  const adminTexts = minePage.data.menus.map(m => m.text)
  check('管理员菜单含「待诊断队列」', adminTexts.includes('待诊断队列'), JSON.stringify(adminTexts))
  check('管理员菜单含「统计概览」', adminTexts.includes('统计概览'))
  check('管理员菜单**不含**「我的收藏」（用户功能）', !adminTexts.includes('我的收藏'))
  check('isAdmin 标志已置位', minePage.data.isAdmin === true)

  // 普通用户：菜单里不应出现管理端入口
  storage.set('role', '0')
  storage.set('userInfo', { id: 2, username: 'bob', nickname: '张三', role: 0 })
  const minePage2 = loadPage('pages/mine/mine.js')
  await minePage2.onShow()
  const userTexts = minePage2.data.menus.map(m => m.text)
  check('普通用户菜单含「我的收藏」', userTexts.includes('我的收藏'), JSON.stringify(userTexts))
  check('普通用户菜单**不含**管理端入口',
    !userTexts.includes('待诊断队列') && !userTexts.includes('统计概览'))
  check('isAdmin 标志为 false', minePage2.data.isAdmin === false)

  // ---- 断言 7：ensureAdmin 三种身份 ----
  header('断言 7：ensureAdmin 对管理员 / 普通用户 / 未登录的处置')
  resetWorld()
  simulateOnLaunch(null)
  await sleep(0)
  const guard7 = freshRequire('utils/auth-guard.js')

  // 管理员 → 放行
  storage.set('token', 'ADMIN_TOKEN')
  storage.set('role', '1')
  check('管理员被放行', (await guard7.ensureAdmin()) === true)
  check('管理员放行时不产生跳转', navigations.length === 0, JSON.stringify(navigations))

  // 普通用户 → 拒绝并回首页（不能傻等在空白页上）
  resetWorld()
  simulateOnLaunch(null)
  await sleep(0)
  const guard7b = freshRequire('utils/auth-guard.js')
  storage.set('token', 'USER_TOKEN')
  storage.set('role', '0')
  check('普通用户被拒绝', (await guard7b.ensureAdmin()) === false)
  await sleep(900)   // 等 toast 后的 setTimeout
  check('普通用户被送回首页（而不是留在空白管理页）',
    navigations.some(n => n.url === '/pages/index/index'), JSON.stringify(navigations))

  // 未登录 → 拒绝并去登录页
  resetWorld()
  simulateOnLaunch(null)
  await sleep(0)
  const guard7c = freshRequire('utils/auth-guard.js')
  check('未登录被拒绝', (await guard7c.ensureAdmin()) === false)
  check('未登录被送到登录页',
    navigations.some(n => n.type === 'reLaunch' && n.url === '/pages/login/login'),
    JSON.stringify(navigations))

  console.log()
  console.log('='.repeat(74))
  const failed = results.filter(r => !r.ok)
  console.log(`  结果：${results.length - failed.length}/${results.length} 通过`)
  console.log('='.repeat(74))
  Module._load = origLoad
  process.exit(failed.length ? 1 : 0)
}

main().catch(e => {
  console.error('测试脚本自身异常：', e)
  process.exit(1)
})
