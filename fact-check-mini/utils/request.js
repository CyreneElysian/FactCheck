const config = require('./config')

function getAuthHeader() {
  const token = wx.getStorageSync('token')
  const role = wx.getStorageSync('role')
  const header = {}
  if (token) {
    // 与 Web 端 authHeader.ts 的做法对齐：统一 String() 后比较。
    // 只认字符串 '1' 时，任何一处写入数字 1 都会让管理员被判成普通用户 → 发错鉴权头 → 401。
    if (String(role) === '1') {
      header.token = token
    } else {
      header.authentication = token
    }
  }
  return header
}

/**
 * 统一请求封装
 * @param {Object} options
 * @param {string} options.url      相对 config.baseUrl 的路径
 * @param {string} [options.method] 默认 GET
 * @param {Object} [options.data]
 * @param {Object} [options.header]
 * @param {boolean} [options.silent] 为 true 时不弹任何提示（用于启动时的静默登录），
 *                                   失败仅通过 Promise reject 返回，避免打扰用户
 */
function request(options) {
  return new Promise((resolve, reject) => {
    const silent = !!options.silent
    const authHeader = getAuthHeader()
    const header = Object.assign({ 'Content-Type': 'application/json' }, authHeader, options.header || {})
    const fullUrl = config.baseUrl + options.url
    // 诊断日志：没带鉴权头的非静默请求，几乎总是"页面没等 loginReady 就发请求"
    // 这个竞态。不提示的话只会看到后端 401 + 莫名跳登录页，很难定位。
    if (!silent && !authHeader.authentication && !authHeader.token) {
      console.warn('[request] 该请求未携带鉴权头（token 尚未就绪？）', options.method || 'GET', options.url)
    }
    // 记录**发出请求这一刻**登录流程是否还在进行 —— 这是区分
    // "竞态型 401"（token 马上就到，不该清）与"真的未登录/已过期"（该清该跳）的**唯一可靠判据**。
    //
    // 不能用"本地是否已有 token"来区分：token 过期/被服务端拒绝时本地同样有 token，
    // 那样会永不清理，用户卡在反复 401、再也回不到登录页（这个缺陷被自测用例 3b 抓出来过）。
    const loginPendingAtDispatch = isLoginPending()
    console.log('[request]', options.method || 'GET', fullUrl, options.data || {})
    wx.request({
      url: fullUrl,
      method: options.method || 'GET',
      data: options.data || {},
      header,
      success(res) {
        if (res.statusCode === 401) {
          handleUnauthorized(options.url, silent, loginPendingAtDispatch)
          reject(res)
          return
        }
        const body = res.data
        if (body && body.code !== 1) {
          if (!silent) {
            wx.showToast({ title: body.msg || '请求失败', icon: 'none' })
          }
          reject(body || res)
          return
        }
        resolve(body)
      },
      fail(err) {
        if (!silent) {
          wx.showToast({ title: '网络异常', icon: 'none' })
        }
        reject(err)
      }
    })
  })
}

/** 登录流程是否还在进行（`loginReady` 已设置且尚未落定） */
function isLoginPending() {
  const app = getApp && getApp()
  const ready = app && app.globalData && app.globalData.loginReady
  if (!ready || typeof ready.then !== 'function') {
    return false
  }
  // 用我们自己挂的状态标志判断，而不是靠"挂过 then"来近似 ——
  // Promise 没有同步的"是否已完成"查询接口，所以由 trackLoginReady 包一层并记录状态。
  return ready.__dshSettled !== true
}

/**
 * 包一层 `loginReady`，记录它是否已落定。
 *
 * 必须在 `app.onLaunch` 设置完 `loginReady` 之后立刻调用。
 * `Promise` 没有同步的"是否已完成"查询接口，而 401 处理是同步逻辑，
 * 因此需要一个显式标志。
 */
function trackLoginReady() {
  const app = getApp && getApp()
  const ready = app && app.globalData && app.globalData.loginReady
  if (!ready || typeof ready.then !== 'function') {
    return
  }
  ready.__dshSettled = false
  Promise.resolve(ready).then(
    () => { ready.__dshSettled = true },
    () => { ready.__dshSettled = true }
  )
}

/** 清除追踪标志（app.onLaunch 重新开始时调用） */
function resetLoginReadyTracking() {
  const app = getApp && getApp()
  const ready = app && app.globalData && app.globalData.loginReady
  if (ready && typeof ready === 'object') {
    ready.__dshSettled = undefined
  }
}

/**
 * 处理 401：区分"竞态型 401"与"真的未登录/已过期"。
 *
 * ## 为什么不能立刻清 token 并跳转（真实 bug）
 *
 * 冷启动时 `app.onLaunch` 的静默登录要一次网络往返，而页面可能在这之前就发了请求，
 * 此时 token 还没写进 storage → 请求不带鉴权头 → 后端返回 401
 * （jjwt 抛 `CharSequence cannot be null or empty`）。
 * 如果立刻 `removeStorageSync('token')` + `reLaunch` 到登录页，就会：
 *   1. 把**正在进行的登录**（token 马上就到）给清掉；
 *   2. 用户看到"刚登录就被踢回登录页"。
 *
 * ## 但也不能"只要本地有 token 就永不清"
 *
 * token 过期/被服务端拒绝时本地同样有 token；那样会让用户卡在反复 401、
 * 永远回不到登录页。所以判据取**请求发出时登录流程是否还在进行**：
 *   * 还在进行 → 竞态型，等 loginReady 落定后再看，通常已有 token，不跳；
 *   * 已落定   → 真的未登录或 token 失效，清残留并跳登录页。
 */
function handleUnauthorized(url, silent, loginPendingAtDispatch) {
  const clearAndRedirect = () => {
    wx.removeStorageSync('token')
    wx.removeStorageSync('role')
    wx.removeStorageSync('userInfo')
    if (silent) return
    wx.showToast({ title: '请先登录', icon: 'none' })
    // 用 reLaunch 而不是 navigateTo：navigateTo 会把登录页压进页面栈，
    // tabBar 被盖住、用户退不回去；且若当前已在登录页还会重复叠栈。
    setTimeout(() => {
      const pages = getCurrentPages()
      const current = pages.length ? pages[pages.length - 1].route : ''
      if (current !== 'pages/login/login') {
        wx.reLaunch({ url: '/pages/login/login' })
      }
    }, 500)
  }

  const app = getApp && getApp()
  const ready = app && app.globalData && app.globalData.loginReady
  const canWait = loginPendingAtDispatch && ready && typeof ready.then === 'function'

  if (!canWait) {
    // 登录流程已结束（或压根没有）：这是真的未登录 / token 已失效
    console.warn('[request] 401 且登录流程已结束，判定为未登录或 token 失效：', url)
    clearAndRedirect()
    return
  }

  console.warn('[request] 401 但请求发出时登录流程仍在进行（疑似竞态），等其落定后再判断：', url)
  const decide = () => {
    if (wx.getStorageSync('token')) {
      // 登录已经成功并写入 token：这是竞态型 401，不清不跳，
      // 交由页面自己的守卫/刷新逻辑重试
      console.warn('[request] 登录已落定且已有 token，忽略这次竞态型 401：', url)
      return
    }
    clearAndRedirect()
  }
  Promise.resolve(ready).then(decide, decide)
}

function get(url, data) { return request({ url, data }) }
function post(url, data) { return request({ url, method: 'POST', data }) }
function put(url, data) { return request({ url, method: 'PUT', data }) }
function del(url) { return request({ url, method: 'DELETE' }) }

module.exports = {
  config,
  request,
  get,
  post,
  put,
  del,
  getAuthHeader,
  // 供 app.onLaunch 在设置 loginReady 后调用：开始追踪"登录流程是否已落定"。
  // 401 处理靠它区分"竞态型 401"与"真的未登录/已过期"。
  trackLoginReady,
  resetLoginReadyTracking
}
