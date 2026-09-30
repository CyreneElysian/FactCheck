const { get } = require('../../utils/request')

Page({
  data: { records: [] },
  onLoad() { this.load() },
  async load() {
    try {
      const res = await get('/authority/list', { page: 1, pageSize: 20 })
      this.setData({ records: res.data.records || [] })
    } catch (e) {}
  }
})
