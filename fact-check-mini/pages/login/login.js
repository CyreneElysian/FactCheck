const { wechatLogin, bindWechat } = require('../../utils/auth')

Page({
  data: {
    needBind: false,
    username: '',
    password: '',
    loading: false,
    loginError: ''
  },
  onLoad() {
    const token = wx.getStorageSync('token')
    if (token) {
      wx.switchTab({ url: '/pages/index/index' })
    }
  },
  onInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ [field]: e.detail.value })
  },
  async onWechatLogin() {
    console.log('[login] 用户点击微信登录')
    this.setData({ loading: true, loginError: '' })
    try {
      const res = await wechatLogin()
      if (res.needBind) {
        this.setData({ needBind: true })
      } else {
        wx.showToast({ title: '登录成功', icon: 'success' })
        wx.switchTab({ url: '/pages/index/index' })
      }
    } catch (e) {
      this.setData({ loginError: (e && e.msg) || '微信登录失败，请检查后端服务是否启动' })
    } finally {
      this.setData({ loading: false })
    }
  },
  async onBind() {
    const { username, password } = this.data
    if (!username || !password) {
      wx.showToast({ title: '请输入用户名和密码', icon: 'none' })
      return
    }
    this.setData({ loading: true })
    try {
      await bindWechat(username, password)
      wx.showToast({ title: '绑定成功', icon: 'success' })
      wx.switchTab({ url: '/pages/index/index' })
    } finally {
      this.setData({ loading: false })
    }
  }
})
