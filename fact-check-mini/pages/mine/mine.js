const { logout } = require('../../utils/auth')

Page({
  data: { userInfo: {}, initial: '用', menus: [] },
  onShow() {
    const userInfo = wx.getStorageSync('userInfo') || {}
    this.setData({
      userInfo,
      initial: (userInfo.nickname || userInfo.username || '用').charAt(0),
      menus: [
        { text: '权威信息', path: '/pages/authority/authority', tab: false },
        { text: '站内信', path: '/pages/messages/messages', tab: false },
        { text: '我的收藏', path: '/pages/favorites/favorites', tab: false },
        { text: '我的申诉', path: '/pages/appeals/appeals', tab: false },
        { text: '个人中心', path: '/pages/profile/profile', tab: false }
      ]
    })
  },
  onMenu(e) {
    const { path, tab } = e.currentTarget.dataset
    if (tab) wx.switchTab({ url: path })
    else wx.navigateTo({ url: path })
  },
  onLogout() {
    logout()
    wx.navigateTo({ url: '/pages/login/login' })
  }
})
