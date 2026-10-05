/**
 * 小程序登录竞态的回归测试（离线、确定性）
 *
 * ## 要钉住的真实 bug
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
 * ## 断言
 *
 * 1. 不带守卫的页面（修复前写法）在冷启动竞态下确实发出**无鉴权头**的请求 —— 复现 bug
 * 2. 带守卫的页面（修复后写法）会等 `loginReady`，请求带上正确鉴权头 —— 修复有效
 * 3. `loginReady` 未落定时的 401 **不**清 token、**不**跳转（避免把即将生效的登录态清掉）
 * 4. `loginReady` 已落定后的 401 → 清残留并跳登录页（否则 token 过期时会卡在反复 401）
 * 5. 守卫在无 token 时返回 false 并用 reLaunch 跳登录页（不压栈）
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
