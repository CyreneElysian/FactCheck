const { get } = require('../../utils/request')

Page({
  data: { list: [] },
  onShow() { this.load() },
  async load() {
    try {
      const res = await get('/hot/top10')
      this.setData({ list: res.data || [] })
    } catch (e) {}
  }
})
