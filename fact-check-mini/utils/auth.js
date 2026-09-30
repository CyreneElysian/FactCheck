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
 * 微信登录
 * @param {Object} [options]
 * @param {boolean} [options.silent] 静默模式：不弹提示，用于 app.onLaunch 自动登录
 */
async function wechatLogin(options = {}) {
  const code = await getCode()
  console.log('[auth] 调用后端 /user/wechat/login, code =', code)
  try {
    const res = await request({
      url: '/user/wechat/login',
      method: 'POST',
      data: { code },
      silent: !!options.silent
    })
    saveLogin(res.data)
    return { success: true }
  } catch (e) {
    console.log('[auth] /user/wechat/login 返回异常', e)
    if (e && e.msg && e.msg.indexOf('未绑定') !== -1) {
      wx.setStorageSync(NEED_BIND_KEY, true)
      return { needBind: true }
    }
    throw e
  }
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

module.exports = { wechatLogin, bindWechat, logout, shouldTrySilentLogin }
