const { get } = require('../../utils/request')

Page({
  data: { id: null, info: {}, loading: true },

  onLoad(options) {
    const id = options && options.id
    if (!id) {
      wx.showToast({ title: '缺少 ID', icon: 'none' })
      return
    }
    this.setData({ id })
    this.load()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    try {
      const res = await get(`/authority/${this.data.id}`)
      this.setData({ info: res.data || {} })
    } catch (e) {
      // 提示由 request 层给出
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 原文链接同样无法在小程序内直接打开，复制给用户 */
  onCopySource() {
    const url = this.data.info.sourceUrl
    if (!url) {
      wx.showToast({ title: '该权威信息没有原文链接', icon: 'none' })
      return
    }
    wx.setClipboardData({
      data: url,
      success: () => wx.showToast({ title: '原文链接已复制', icon: 'none' })
    })
  },

  onPreviewCover() {
    const url = this.data.info.coverImage
    if (url) wx.previewImage({ urls: [url], current: url })
  },

  onShareAppMessage() {
    const info = this.data.info || {}
    return {
      title: info.title || '权威信息',
      path: `/pages/authority-detail/authority-detail?id=${this.data.id}`
    }
  },

  onShareTimeline() {
    const info = this.data.info || {}
    return { title: info.title || '权威信息', query: `id=${this.data.id}` }
  }
})
