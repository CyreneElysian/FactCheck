const { get } = require('../../utils/request')

Page({
  data: { records: [] },
  onShow() { this.load() },
  async load() {
    try {
      const res = await get('/appeal/my', { page: 1, pageSize: 20 })
      this.setData({ records: res.data.records || [] })
    } catch (e) {}
  }
})
