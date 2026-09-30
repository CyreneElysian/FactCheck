const { get, del } = require('../../utils/request')

Page({
  data: { records: [] },
  onShow() { this.load() },
  async load() {
    try {
      const res = await get('/favorite/my', { page: 1, pageSize: 20 })
      this.setData({ records: res.data.records || [] })
    } catch (e) {}
  },
  async onRemove(e) {
    await del(`/favorite/${e.currentTarget.dataset.id}`)
    wx.showToast({ title: '已取消', icon: 'success' })
    this.load()
  }
})
