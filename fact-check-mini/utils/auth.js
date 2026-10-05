const { post, request } = require('./request')

/** 已判定"需要绑定 Web 账号"，避免每次启动都发起无效的静默登录 */
const NEED_BIND_KEY = 'wechatNeedBind'
/** 用户主动退出登录后，本次不再自动静默登录 */
const SILENT_DISABLED_KEY = 'wechatSilentDisabled'

function getCode() {
  console.log('[auth] 开始调用 wx.login ...')
  return new Promise((resolve, reject) => {
    wx.login({
      success: res => {
        if (res.code) {
          console.log('[auth] wx.login 成功, code =', res.code)
          resolve(res.code)
        } else {
          console.error('[auth] wx.login 未返回 code', res)
          reject(res)
        }
      },
      fail: err => {
        console.error('[auth] wx.login 失败', err)
        reject(err)
      }
    })
  })
}

function saveLogin(data) {
  console.log('[auth] 保存登录信息, role =', data.role)
  wx.setStorageSync('token', data.token)
  wx.setStorageSync('role', String(data.role))
  wx.setStorageSync('userInfo', data)
  // 登录成功后重置静默登录相关的标记
  wx.removeStorageSync(NEED_BIND_KEY)
  wx.removeStorageSync(SILENT_DISABLED_KEY)
  getApp().globalData.userInfo = data
}

/**
 * 启动时是否值得尝试静默登录
 * 需要绑定、或用户刚主动退出时，都不再自动登录
 */
function shouldTrySilentLogin() {
  return !wx.getStorageSync(NEED_BIND_KEY) && !wx.getStorageSync(SILENT_DISABLED_KEY)
}

/**
 * 识别当前微信的绑定状态（不落登录态）
 *
 * 小程序端在"该微信未绑定任何账号"时要给用户**两个选择**：创建新账号 或 绑定已有账号。
 * 因此需要先知道属于哪种情况，再决定显示哪个表单 —— 而不是像以前那样直接甩出绑定表单。
 *
 * @param {Object} [options]
 * @param {boolean} [options.silent] 静默模式：不弹提示（用于启动时自动识别）
 * @returns {Promise<{loggedIn?: boolean, needBind?: boolean}>}
 *          loggedIn = 已绑定，登录态已保存；needBind = 未绑定，需要用户选一条路
 */
async function identifyAccount(options = {}) {
  const code = await getCode()
  console.log('[auth] 识别绑定状态：调用后端 /user/wechat/login')
  try {
    const res = await request({
      url: '/user/wechat/login',
      method: 'POST',
      data: { code },
      silent: !!options.silent
    })
    saveLogin(res.data)
    return { loggedIn: true }
  } catch (e) {
    if (isNotBoundError(e)) {
      wx.setStorageSync(NEED_BIND_KEY, true)
      return { needBind: true }
    }
    throw e
  }
}

/**
 * 判断错误是否为"该微信未绑定账号"
 *
 * 后端用 AccountNotFoundException + MessageConstant.WECHAT_NOT_BOUND 表示该情况，
 * 走的是**业务异常**（code != 1）而不是 HTTP 401，因此不能靠状态码判断。
 */
function isNotBoundError(e) {
  return !!(e && e.msg && String(e.msg).indexOf('未绑定') !== -1)
}

/**
 * 微信登录（保留原语义：已绑定则登录，未绑定则返回 needBind）
 * @param {Object} [options]
 * @param {boolean} [options.silent] 静默模式：不弹提示，用于 app.onLaunch 自动登录
 */
async function wechatLogin(options = {}) {
  const res = await identifyAccount(options)
  if (res.needBind) {
    return { needBind: true }
  }
  return { success: true }
}

/**
 * 小程序端**创建新账号**（可选同时绑定当前微信）
 *
 * 这是与 {@link bindWechat} 并列的另一条路：用户没有 Web 账号时也能直接注册，
 * 不必先去 Web 端注册再回来绑定。
 *
 * 后端在同一次请求里完成"注册 + 绑定"（同一事务），因此不存在
 * "账号建好了但微信没绑上、用户仍然登不进去"的中间态。
 *
 * @param {string} username 用户名
 * @param {string} password 密码（8~64 位，须同时包含字母与数字）
 * @param {string} [nickname] 昵称，留空则后端取用户名
 */
async function registerByWechat(username, password, nickname) {
  const code = await getCode()
  console.log('[auth] 调用后端 /user/register（带 code，注册并绑定微信）')
  const res = await post('/user/register', { code, username, password, nickname })
  // 后端在带 code 时直接返回登录态，省掉一次登录往返
  saveLogin(res.data)
  return { success: true }
}

async function bindWechat(username, password) {
  const code = await getCode()
  console.log('[auth] 调用后端 /user/wechat/bind, code =', code, ', username =', username)
  const res = await post('/user/wechat/bind', { code, username, password })
  saveLogin(res.data)
  return { success: true }
}

function logout() {
  wx.removeStorageSync('token')
  wx.removeStorageSync('role')
  wx.removeStorageSync('userInfo')
  // 主动退出后不再自动静默登录，否则会立刻把用户重新登回去
  wx.setStorageSync(SILENT_DISABLED_KEY, true)
  getApp().globalData.userInfo = null
}

module.exports = {
  wechatLogin,
  identifyAccount,
  bindWechat,
  registerByWechat,
  logout,
  shouldTrySilentLogin
}
