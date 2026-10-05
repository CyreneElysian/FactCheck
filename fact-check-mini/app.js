const { wechatLogin, shouldTrySilentLogin } = require('./utils/auth')
const { trackLoginReady, resetLoginReadyTracking } = require('./utils/request')

App({
  globalData: {
    userInfo: null,
    /**
     * 静默登录的 Promise。页面 onLoad 里 await 它，避免与 onLaunch 的登录竞争，
     * 否则可能出现"静默登录尚未完成就被判定为未登录"而误跳登录页。
     */
    loginReady: null
  },

  onLaunch() {
    // 每次启动都重新开始追踪"登录流程是否已落定"：
    // request.js 的 401 处理靠它区分「竞态型 401」（token 马上就到，不该清）
    // 与「真的未登录/token 已失效」（该清该跳登录页）。
    resetLoginReadyTracking()

    // 已有 token：直接复用本地登录态
    if (wx.getStorageSync('token')) {
      this.globalData.userInfo = wx.getStorageSync('userInfo') || null
      this.globalData.loginReady = Promise.resolve({ cached: true })
      trackLoginReady()
      return
    }
    // 需要绑定 Web 账号、或用户刚主动退出：不做静默登录
    if (!shouldTrySilentLogin()) {
      console.log('[app] 跳过静默登录（需绑定 Web 账号或用户已主动退出）')
      this.globalData.loginReady = Promise.resolve({ skipped: true })
      trackLoginReady()
      return
    }
    this.globalData.loginReady = this.silentLogin()
    trackLoginReady()
  },

  /**
   * 启动时静默微信登录：已绑定账号的用户无需再输入账号密码（产品文档 4.1.3）
   */
  async silentLogin() {
    try {
      const res = await wechatLogin({ silent: true })
      console.log('[app] 静默登录结果：', res)
      return res
    } catch (e) {
      console.log('[app] 静默登录失败，等待用户手动登录', e)
      return { failed: true }
    }
  }
})
