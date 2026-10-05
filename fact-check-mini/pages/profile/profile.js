const { get, put, post, config, getAuthHeader } = require('../../utils/request')
const { logout } = require('../../utils/auth')
const { ensureLogin, goLogin } = require('../../utils/auth-guard')

Page({
  data: {
    form: { nickname: '', avatar: '' },
    avatarPreview: '',
    saving: false,
    // 修改密码
    pwd: { oldPassword: '', newPassword: '', confirm: '' },
    pwdBusy: false,
    // 注销账号
    cancelBusy: false
  },

  async onLoad() {
    if (!(await ensureLogin())) return
    this.load()
  },

  onShow() {
    // 从别处改过头像/昵称后回到本页时同步一次
    this.load()
  },

  async load() {
    try {
      const res = await get('/user/profile')
      const data = res.data || {}
      this.setData({
        form: { nickname: data.nickname || '', avatar: data.avatar || '' },
        avatarPreview: data.avatar || ''
      })
    } catch (e) {
      // 提示由 request 层给出
    }
  },

  onNickname(e) { this.setData({ 'form.nickname': e.detail.value }) },

  async onSave() {
    if (this.data.saving) return
    this.setData({ saving: true })
    try {
      await put('/user/profile', this.data.form)
      // 同步本地缓存，避免"我的"页仍显示旧昵称/头像
      const userInfo = wx.getStorageSync('userInfo') || {}
      userInfo.nickname = this.data.form.nickname
      userInfo.avatar = this.data.form.avatar
      wx.setStorageSync('userInfo', userInfo)
      wx.showToast({ title: '已保存', icon: 'success' })
    } catch (e) {
      // 提示由 request 层给出
    } finally {
      this.setData({ saving: false })
    }
  },

  /**
   * 更换头像（P3-32）
   *
   * 先用 /common/upload 上传拿到 OSS 地址，再用 /user/profile 保存——
   * 与 Web 端上传链路一致，避免把本地临时路径存进数据库。
   */
  onChooseAvatar() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      success: res => {
        const filePath = res.tempFiles[0].tempFilePath
        wx.showLoading({ title: '上传中...' })
        wx.uploadFile({
          url: config.baseUrl + '/common/upload',
          filePath,
          name: 'file',
          header: getAuthHeader(),
          success: async uploadRes => {
            let body = {}
            try {
              body = JSON.parse(uploadRes.data)
            } catch (e) {
              wx.hideLoading()
              wx.showToast({ title: '上传失败', icon: 'none' })
              return
            }
            if (body.code !== 1) {
              wx.hideLoading()
              wx.showToast({ title: body.msg || '上传失败', icon: 'none' })
              return
            }
            const url = body.data
            try {
              await put('/user/profile', { nickname: this.data.form.nickname, avatar: url })
              const userInfo = wx.getStorageSync('userInfo') || {}
              userInfo.avatar = url
              wx.setStorageSync('userInfo', userInfo)
              this.setData({ 'form.avatar': url, avatarPreview: filePath })
              wx.showToast({ title: '头像已更新', icon: 'success' })
            } catch (e) {
              wx.showToast({ title: '保存头像失败', icon: 'none' })
            } finally {
              wx.hideLoading()
            }
          },
          fail: () => {
            wx.hideLoading()
            wx.showToast({ title: '上传失败', icon: 'none' })
          }
        })
      }
    })
  },

  onPwdInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ ['pwd.' + field]: e.detail.value })
  },

  /** 修改密码（P3-32） */
  async onChangePassword() {
    const { oldPassword, newPassword, confirm } = this.data.pwd
    if (!oldPassword || !newPassword) {
      wx.showToast({ title: '请填写完整', icon: 'none' })
      return
    }
    if (newPassword !== confirm) {
      wx.showToast({ title: '两次输入的新密码不一致', icon: 'none' })
      return
    }
    if (this.data.pwdBusy) return
    this.setData({ pwdBusy: true })
    try {
      await put('/user/password', { oldPassword, newPassword })
      this.setData({ pwd: { oldPassword: '', newPassword: '', confirm: '' } })
      wx.showToast({ title: '密码已修改，请重新登录', icon: 'none' })
      setTimeout(() => {
        logout()
        goLogin()
      }, 1200)
    } catch (e) {
      // 提示由 request 层给出
    } finally {
      this.setData({ pwdBusy: false })
    }
  },

  /**
   * 注销账号（P3-32）
   *
   * 不可逆操作，走二次确认；注销后清理本地登录态并回到登录页。
   */
  onCancelAccount() {
    wx.showModal({
      title: '注销账号',
      content: '注销后账号将无法登录，且该操作不可恢复。确定继续吗？',
      confirmText: '确认注销',
      confirmColor: '#f56c6c',
      success: async res => {
        if (!res.confirm) return
        if (this.data.cancelBusy) return
        this.setData({ cancelBusy: true })
        try {
          await post('/user/cancel')
          wx.showToast({ title: '账号已注销', icon: 'none' })
          logout()
          setTimeout(() => goLogin(), 1200)
        } catch (e) {
          // 提示由 request 层给出
        } finally {
          this.setData({ cancelBusy: false })
        }
      }
    })
  }
})
