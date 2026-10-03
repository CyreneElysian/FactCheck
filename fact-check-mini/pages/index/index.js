const { get, post, config, getAuthHeader } = require('../../utils/request')

Page({
  data: {
    categories: ['其他', '健康', '社会', '财经', '科技', '政策'],
    categoryIndex: 0,
    content: '',
    // OSS 图片地址（提交图片检测时使用）
    imageUrl: '',
    // 本地临时文件路径，用于即时预览
    previewPath: '',
    submitting: false,
    result: null,
    verdictText: '',
    verdictClass: ''
  },

  async onLoad() {
    // 等待 app.onLaunch 的静默登录完成，避免误判为未登录
    const app = getApp()
    if (app && app.globalData.loginReady) {
      await app.globalData.loginReady
    }
    if (!wx.getStorageSync('token')) {
      wx.navigateTo({ url: '/pages/login/login' })
    }
  },

  onContentInput(e) { this.setData({ content: e.detail.value }) },

  onCategoryChange(e) { this.setData({ categoryIndex: Number(e.detail.value) }) },

  async onSubmit() {
    const content = this.data.content.trim()
    if (!content) {
      wx.showToast({ title: '请输入消息内容', icon: 'none' })
      return
    }
    this.setData({ submitting: true, result: null })
    try {
      // 有图片时按"图片检测"提交并带上图片地址（技术方案 8.3）
      const payload = {
        content,
        sourceType: this.data.imageUrl ? 2 : 1,
        category: this.data.categories[this.data.categoryIndex]
      }
      if (this.data.imageUrl) {
        payload.imageUrl = this.data.imageUrl
      }
      const res = await post('/detect/submit', payload)
      this.pollRecord(res.data)
    } finally {
      this.setData({ submitting: false })
    }
  },

  pollRecord(recordId) {
    const timer = setInterval(async () => {
      try {
        const res = await get(`/detect/record/${recordId}`)
        this.applyResult(res.data)
        if (res.data.status !== 0) clearInterval(timer)
      } catch (e) {
        clearInterval(timer)
      }
    }, 2000)
  },

  applyResult(result) {
    let verdictText = '待诊断'
    if (result.status === 3) {
      verdictText = '检测失败'
    } else if (result.verdict === 1) {
      verdictText = '真'
    } else if (result.verdict === 2) {
      verdictText = '假'
    } else if (result.verdict === 3) {
      verdictText = '无法判定'
    }
    const verdictClass = result.verdict === 1 ? 'verdict-true' : result.verdict === 2 ? 'verdict-false' : 'verdict-unknown'
    this.setData({ result, verdictText, verdictClass })
  },

  goDetail() {
    if (this.data.result && this.data.result.id) {
      wx.navigateTo({ url: `/pages/detail/detail?id=${this.data.result.id}` })
    }
  },

  onChooseImage() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      success: res => {
        const filePath = res.tempFiles[0].tempFilePath
        wx.showLoading({ title: '识别中...' })
        wx.uploadFile({
          url: config.baseUrl + '/ocr/recognize',
          filePath,
          name: 'file',
          header: getAuthHeader(),
          success: uploadRes => {
            wx.hideLoading()
            try {
              const body = JSON.parse(uploadRes.data)
              if (body.code !== 1) {
                wx.showToast({ title: body.msg || '识别失败', icon: 'none' })
                return
              }
              // 同时保存 OSS 地址（提交用）与本地路径（预览用）
              this.setData({
                content: body.data.text || '',
                imageUrl: body.data.imageUrl || '',
                previewPath: filePath
              })
              wx.showToast({ title: '识别完成，可编辑后提交', icon: 'none' })
            } catch (e) {
              wx.showToast({ title: '识别失败', icon: 'none' })
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

  /** 移除已识别的图片，回到纯文字检测 */
  onRemoveImage() {
    this.setData({ imageUrl: '', previewPath: '' })
  },

  /** 原生分享（P3-31）：首页分享给好友 */
  onShareAppMessage() {
    return {
      title: '事实检测：AI + 人工的事实核查，帮你判断消息真假',
      path: '/pages/index/index'
    }
  },

  onShareTimeline() {
    return { title: '事实检测：AI + 人工的事实核查，帮你判断消息真假' }
  }
})
