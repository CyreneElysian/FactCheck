const { get, put } = require('../../utils/request')

Page({
  data: { records: [] },
  onShow() { this.load() },
  async load() {
    try {
      const res = await get('/message/list', { page: 1, pageSize: 20 })
      this.setData({ records: res.data.records || [] })
    } catch (e) {}
  },
  async onRead(e) {
    await put(`/message/${e.currentTarget.dataset.id}/read`)
    this.load()
  },
  async onReadAll() {
    await put('/message/read-all')
    this.load()
  }
})
