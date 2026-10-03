const { logout } = require('../../utils/auth')
const { get } = require('../../utils/request')

Page({
  data: { userInfo: {}, initial: '用', avatar: '', unread: 0, menus: [] },

  onShow() {
    const userInfo = wx.getStorageSync('userInfo') || {}
    this.setData({
      userInfo,
      avatar: userInfo.avatar || '',
      initial: (userInfo.nickname || userInfo.username || '用').charAt(0),
      menus: [
        { text: '权威信息', path: '/pages/authority/authority', tab: false },
        { text: '站内信', path: '/pages/messages/messages', tab: false, badge: true },
        { text: '我的收藏', path: '/pages/favorites/favorites', tab: false },
        { text: '我的申诉', path: '/pages/appeals/appeals', tab: false },
        { text: '个人中心', path: '/pages/profile/profile', tab: false }
      ]
    })
    this.loadUnread()
  },

  /** 站内信未读红点（P3-32：原先完全不调 /message/unread-count） */
  async loadUnread() {
    if (!wx.getStorageSync('token')) {
      this.setData({ unread: 0 })
      return
    }
    try {
      const res = await get('/message/unread-count')
      this.setData({ unread: Number(res.data) || 0 })
    } catch (e) {
      // 未登录/网络异常时不显示红点即可
    }
  },

  onMenu(e) {
    const { path, tab } = e.currentTarget.dataset
    if (tab) wx.switchTab({ url: path })
    else wx.navigateTo({ url: path })
  },

  onLogout() {
    logout()
    this.setData({ unread: 0, avatar: '', userInfo: {} })
    wx.navigateTo({ url: '/pages/login/login' })
  }
})
