const { wechatLogin, bindWechat, registerByWechat } = require('../../utils/auth')
const { goHome } = require('../../utils/auth-guard')

/** 密码强度规则的最小长度（与后端 ValidationUtils.MIN_PASSWORD_LENGTH 保持一致） */
const MIN_PASSWORD_LENGTH = 8

Page({
  data: {
    /**
     * 当前处于哪一步：
     *   entry  = 只显示"微信一键登录"
     *   choose = 该微信未绑定 → 让用户选「创建新账号」或「绑定已有账号」
     */
    step: 'entry',
    /** choose 步里选中的方式：create | bind */
    mode: 'create',
    username: '',
    password: '',
    nickname: '',
    loading: false,
    loginError: ''
  },

  async onLoad() {
    // 若 app.onLaunch 的静默登录已成功，直接进入首页，无需再手动点登录
    const app = getApp()
    if (app && app.globalData.loginReady) {
      try {
        await app.globalData.loginReady
      } catch (e) {
        // 静默登录失败不算错误，继续按未登录处理
      }
    }
    if (wx.getStorageSync('token')) {
      goHome()
    }
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ [field]: e.detail.value })
  },

  /** 微信一键登录：已绑定则直接进；未绑定则进入"选一条路"这一步 */
  async onWechatLogin() {
    console.log('[login] 用户点击微信登录')
    this.setData({ loading: true, loginError: '' })
    try {
      const res = await wechatLogin()
      if (res.needBind) {
        // 关键改动：不再直接甩出"绑定 Web 账号"表单，而是让用户自己选
        this.setData({ step: 'choose', mode: 'create' })
      } else {
        wx.showToast({ title: '登录成功', icon: 'success' })
        goHome()
      }
    } catch (e) {
      this.setData({ loginError: (e && e.msg) || '微信登录失败，请检查后端服务是否启动' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onChooseCreate() {
    // 切换方式时清掉密码，避免把绑定用的密码带到注册表单里
    this.setData({ mode: 'create', password: '', loginError: '' })
  },

  onChooseBind() {
    this.setData({ mode: 'bind', password: '', loginError: '' })
  },

  /** 创建新账号（后端在同一事务里注册 + 绑定微信，并直接返回登录态） */
  async onRegister() {
    const { username, password, nickname } = this.data
    if (!username || !password) {
      wx.showToast({ title: '请输入用户名和密码', icon: 'none' })
      return
    }
    // 前端先做一次基本校验，给出比后端更具体的提示（后端也会再校验一次）
    if (password.length < MIN_PASSWORD_LENGTH) {
      wx.showToast({ title: `密码至少 ${MIN_PASSWORD_LENGTH} 位`, icon: 'none' })
      return
    }
    if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) {
      wx.showToast({ title: '密码须同时包含字母和数字', icon: 'none' })
      return
    }
    this.setData({ loading: true, loginError: '' })
    try {
      await registerByWechat(username, password, nickname)
      wx.showToast({ title: '创建成功', icon: 'success' })
      goHome()
    } catch (e) {
      this.setData({ loginError: (e && e.msg) || '创建失败，请稍后重试' })
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
    this.setData({ loading: true, loginError: '' })
    try {
      await bindWechat(username, password)
      wx.showToast({ title: '绑定成功', icon: 'success' })
      goHome()
    } catch (e) {
      this.setData({ loginError: (e && e.msg) || '绑定失败，请检查用户名和密码' })
    } finally {
      this.setData({ loading: false })
    }
  }
})
