const { post } = require('./request')

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
  getApp().globalData.userInfo = data
}

async function wechatLogin() {
  const code = await getCode()
  console.log('[auth] 调用后端 /user/wechat/login, code =', code)
  try {
    const res = await post('/user/wechat/login', { code })
    saveLogin(res.data)
    return { success: true }
  } catch (e) {
    console.log('[auth] /user/wechat/login 返回异常', e)
    if (e && e.msg && e.msg.indexOf('未绑定') !== -1) {
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
  getApp().globalData.userInfo = null
}

module.exports = { wechatLogin, bindWechat, logout }
