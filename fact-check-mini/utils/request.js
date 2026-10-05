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
    const header = Object.assign({ 'Content-Type': 'application/json' }, getAuthHeader(), options.header || {})
    const fullUrl = config.baseUrl + options.url
    console.log('[request]', options.method || 'GET', fullUrl, options.data || {})
    wx.request({
      url: fullUrl,
      method: options.method || 'GET',
      data: options.data || {},
      header,
      success(res) {
        if (res.statusCode === 401) {
          wx.removeStorageSync('token')
          wx.removeStorageSync('role')
          wx.removeStorageSync('userInfo')
          if (!silent) {
            wx.showToast({ title: '请先登录', icon: 'none' })
            // 用 reLaunch 而不是 navigateTo：
            //   navigateTo 会把登录页压进页面栈，tabBar 被盖住，用户"退不回去"；
            //   且若当前已在登录页，navigateTo 还会重复叠栈。
            // reLaunch 会关闭所有页面再打开，任何位置都能安全跳转。
            setTimeout(() => {
              const pages = getCurrentPages()
              const current = pages.length ? pages[pages.length - 1].route : ''
              if (current !== 'pages/login/login') {
                wx.reLaunch({ url: '/pages/login/login' })
              }
            }, 500)
          }
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

function get(url, data) { return request({ url, data }) }
function post(url, data) { return request({ url, method: 'POST', data }) }
function put(url, data) { return request({ url, method: 'PUT', data }) }
function del(url) { return request({ url, method: 'DELETE' }) }

module.exports = { config, request, get, post, put, del, getAuthHeader }
