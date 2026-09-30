const config = require('./config')

function getAuthHeader() {
  const token = wx.getStorageSync('token')
  const role = wx.getStorageSync('role')
  const header = {}
  if (token) {
    if (role === '1') {
      header.token = token
    } else {
      header.authentication = token
    }
  }
  return header
}

function request(options) {
  return new Promise((resolve, reject) => {
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
          wx.showToast({ title: '请先登录', icon: 'none' })
          setTimeout(() => wx.navigateTo({ url: '/pages/login/login' }), 500)
          reject(res)
          return
        }
        const body = res.data
        if (body && body.code !== 1) {
          wx.showToast({ title: body.msg || '请求失败', icon: 'none' })
          reject(body || res)
          return
        }
        resolve(body)
      },
      fail(err) {
        wx.showToast({ title: '网络异常', icon: 'none' })
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
