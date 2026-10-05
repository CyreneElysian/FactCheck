const { logout } = require('../../utils/auth')
const { get } = require('../../utils/request')
const { ensureLogin, goLogin, isAdmin } = require('../../utils/auth-guard')

/** 普通用户菜单 */
const USER_MENUS = [
  { text: '权威信息', path: '/pages/authority/authority', tab: false },
  { text: '站内信', path: '/pages/messages/messages', tab: false, badge: true },
  { text: '我的收藏', path: '/pages/favorites/favorites', tab: false },
  { text: '我的申诉', path: '/pages/appeals/appeals', tab: false },
  { text: '个人中心', path: '/pages/profile/profile', tab: false }
]

/**
 * 管理员菜单
 *
 * 与 Web 管理端（8 个控制器 / 约 25 个接口）不同，这里**只放手机上真正用得上**的三项：
 *   * 待诊断：管理员的日常工作，适合手机上随时处理；
 *   * 申诉处理：表单轻，适合移动端；
 *   * 统计概览：只读，成本极低。
 * 刻意**不搬**结论管理（重表单 + 修订原因）、权威信息 CRUD（长文本录入）、
 * 账号管理/操作日志（低频敏感操作）—— 那些在 PC 上做更合适。
 */
const ADMIN_MENUS = [
  { text: '待诊断队列', path: '/pages/admin/pending/pending', tab: false },
  { text: '申诉处理', path: '/pages/admin/appeals/appeals', tab: false },
  { text: '统计概览', path: '/pages/admin/stats/stats', tab: false },
  { text: '权威信息', path: '/pages/authority/authority', tab: false },
  { text: '站内信', path: '/pages/messages/messages', tab: false, badge: true },
  { text: '个人中心', path: '/pages/profile/profile', tab: false }
]

Page({
  data: {
    userInfo: {},
    initial: '用',
    avatar: '',
    unread: 0,
    menus: [],
    /** 是否管理员：决定「我的」页渲染管理端还是用户端菜单 */
    isAdmin: false
  },

  async onShow() {
    // 登录守卫：未登录直接去登录页，不再渲染半个"我的"页面
    // （onShow 而非 onLoad：从登录页回来后要能重新渲染）
    if (!(await ensureLogin())) return

    // ★ 角色分支：直接读 storage 里的 role，**不依赖登录流程**
    //
    // 为什么不在"登录时"判断：冷启动复用缓存 token 时走的是
    // `app.onLaunch` 的 `if (wx.getStorageSync('token'))` 直接复用分支，
    // **根本不经过登录流程**，登录时的分支会被跳过 → 管理员看到用户界面。
    // 而 onShow 每次进入都执行，且 role 已持久化在 storage，读它零成本。
    const admin = isAdmin()

    const userInfo = wx.getStorageSync('userInfo') || {}
    this.setData({
      isAdmin: admin,
      userInfo,
      avatar: userInfo.avatar || '',
      initial: (userInfo.nickname || userInfo.username || '用').charAt(0),
      menus: admin ? ADMIN_MENUS : USER_MENUS
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
    // reLaunch 清空页面栈；navigateTo 会把登录页压栈、tabBar 被盖住
    goLogin()
  }
})
