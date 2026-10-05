/**
 * 小程序登录流程的**运行期模拟**（不依赖微信开发者工具）
 *
 * 目的：定位"登录后请求仍带空 token"的真实原因。
 * 做法：造一个假的 `wx`（含 storage / login / request / reLaunch 等），
 * 按真实顺序 require 小程序自己的 utils 与页面逻辑，把**每次请求的头**打出来。
 *
 * 用法：node scripts/simulate-mini-login.js
 */

const path = require('path')
const Module = require('module')

const MINI = path.join(__dirname, '..')
const storage = new Map()
const requests = []
const navigations = []

// ---- 假 wx ----
const wx = {
  setStorageSync: (k, v) => storage.set(k, v),
  getStorageSync: k => (storage.has(k) ? storage.get(k) : ''),
  removeStorageSync: k => storage.delete(k),
  login: ({ success }) => success({ code: 'FAKE_CODE' }),
  showToast: () => {},
  showLoading: () => {},
  hideLoading: () => {},
  request: opts => {
    requests.push({ url: opts.url, method: opts.method, header: opts.header, data: opts.data })
    // 由脚本在调用前设定下一步响应
    const resp = global.__nextResponse || { statusCode: 200, data: { code: 1, msg: 'ok', data: {} } }
    setImmediate(() => opts.success(resp))
  },
  reLaunch: o => navigations.push({ type: 'reLaunch', url: o.url }),
  switchTab: o => navigations.push({ type: 'switchTab', url: o.url }),
  navigateTo: o => navigations.push({ type: 'navigateTo', url: o.url })
}

let appInstance = null
global.wx = wx
global.getApp = () => appInstance
global.getCurrentPages = () => [{ route: 'pages/index/index' }]

// 用 Module._load 钩子在 require('../../app')/Page/App 时返回我们的桩
const origLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (request === 'wx') return wx
  return origLoad.apply(this, arguments)
}
global.App = cfg => {
  appInstance = Object.assign({ globalData: {} }, cfg)
  if (appInstance.globalData === undefined) appInstance.globalData = {}
  // 保留 cfg.globalData
  appInstance.globalData = cfg.globalData || {}
  if (cfg.onLaunch) cfg.onLaunch.call(appInstance)
}
global.Page = cfg => {
  global.__lastPage = cfg
}

// ---- 载入小程序的真实模块 ----
require(path.join(MINI, 'app.js'))   // 触发 onLaunch

const auth = require(path.join(MINI, 'utils', 'auth.js'))
const guard = require(path.join(MINI, 'utils', 'auth-guard.js'))

function banner(t) {
  console.log('\n' + '='.repeat(72))
  console.log('  ' + t)
  console.log('='.repeat(72))
}

function dumpRequests(label) {
  console.log(`  --- ${label}：本轮请求 ${requests.length} 条 ---`)
  requests.forEach((r, i) => {
    const h = r.header || {}
    const authVal = h.authentication !== undefined ? `authentication=${JSON.stringify(h.authentication)}`
      : h.token !== undefined ? `token=${JSON.stringify(h.token)}` : '（无鉴权头）'
    console.log(`    ${i + 1}) ${r.method} ${r.url}`)
    console.log(`       ${authVal}`)
  })
  requests.length = 0
}

;(async () => {
  banner('场景 1：全新启动（无 token）→ onLaunch 静默登录返回"未绑定"')
  global.__nextResponse = { statusCode: 200, data: { code: 0, msg: '该微信未绑定账号', data: null } }
  // 重新跑一次 onLaunch
  appInstance.onLaunch()
  try { await appInstance.globalData.loginReady } catch (e) { console.log('  loginReady reject:', e && e.msg) }
  console.log('  storage:', JSON.stringify([...storage.entries()]))
  dumpRequests('静默登录')

  banner('场景 2：用户在登录页选「创建新账号」→ 调 /user/register（带 code）')
  global.__nextResponse = {
    statusCode: 200,
    data: { code: 1, msg: 'ok', data: { id: 5, username: 'newbie', role: 0, token: 'TOKEN_FROM_REGISTER' } }
  }
  await auth.registerByWechat('newbie', 'abcd1234', '新用户')
  console.log('  storage:', JSON.stringify([...storage.entries()]))
  dumpRequests('注册请求')

  banner('场景 3：注册后 tab 页发请求（mine.onShow 的守卫 + loadUnread）')
  global.__nextResponse = { statusCode: 200, data: { code: 1, msg: 'ok', data: 0 } }
  const okGuard = await guard.ensureLogin()
  console.log('  ensureLogin() =', okGuard, '（true = 已登录可继续）')
  const { get } = require(path.join(MINI, 'utils', 'request.js'))
  await get('/message/unread-count')
  dumpRequests('tab 页请求')

  banner('场景 4：token 为空字符串时会发生什么（模拟 storage 被写坏）')
  storage.set('token', '')
  try {
    await get('/history/page')
  } catch (e) { /* 401 已 reject */ }
  dumpRequests('token 为空时')
  console.log('  导航记录:', JSON.stringify(navigations))

  banner('结论')
  console.log('  getAuthHeader() =', JSON.stringify(require(path.join(MINI, 'utils', 'request.js')).getAuthHeader()))
})().catch(e => {
  console.error('模拟脚本自身异常：', e)
  process.exit(1)
})
