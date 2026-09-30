const { get, post, config, getAuthHeader } = require('../../utils/request')

Page({
  data: {
    categories: ['其他', '健康', '社会', '财经', '科技', '政策'],
    categoryIndex: 0,
    content: '',
    submitting: false,
    result: null,
    verdictText: '',
    verdictClass: ''
  },
  onLoad() {
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
      const res = await post('/detect/submit', {
        content,
        sourceType: 1,
        category: this.data.categories[this.data.categoryIndex]
      })
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
    const verdictText = result.verdict === 1 ? '真' : result.verdict === 2 ? '假' : result.verdict === 3 ? '无法判定' : '待诊断'
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
              this.setData({ content: body.data.text || '' })
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
  }
})
