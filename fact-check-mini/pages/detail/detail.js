const { get } = require('../../utils/request')

Page({
  data: { id: null, record: {}, verdictText: '' },
  onLoad(options) {
    this.setData({ id: options.id })
    this.load(options.id)
  },
  async load(id) {
    try {
      const res = await get(`/detect/record/${id}`)
      const record = res.data
      const verdictText = record.verdict === 1 ? '真' : record.verdict === 2 ? '假' : record.verdict === 3 ? '无法判定' : '待诊断'
      this.setData({ record, verdictText })
    } catch (e) {}
  }
})
