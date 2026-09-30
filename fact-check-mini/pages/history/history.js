const { get } = require('../../utils/request')

Page({
  data: { records: [] },
  onShow() { this.load() },
  async load() {
    try {
      const res = await get('/detect/records', { page: 1, pageSize: 20 })
      this.setData({ records: res.data.records || [] })
    } catch (e) {}
  },
  goDetail(e) {
    wx.navigateTo({ url: `/pages/detail/detail?id=${e.currentTarget.dataset.id}` })
  }
})
