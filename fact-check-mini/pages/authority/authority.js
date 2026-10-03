const { get } = require('../../utils/request')

const PAGE_SIZE = 10

Page({
  data: { records: [], page: 1, total: 0, keyword: '', loading: false, finished: false },

  onLoad() { this.reload() },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh())
  },

  /** 触底加载更多（P3-33） */
  onReachBottom() { this.loadMore() },

  onKeyword(e) { this.setData({ keyword: e.detail.value }) },

  onSearch() { this.reload() },

  reload() {
    this.setData({ page: 1, records: [], finished: false })
    return this.loadMore()
  },

  async loadMore() {
    if (this.data.loading || this.data.finished) return
    this.setData({ loading: true })
    const page = this.data.page
    const query = { page, pageSize: PAGE_SIZE }
    if (this.data.keyword) query.keyword = this.data.keyword
    try {
      const res = await get('/authority/list', query)
      const data = res.data || {}
      const incoming = data.records || []
      this.setData({
        records: page === 1 ? incoming : this.data.records.concat(incoming),
        total: data.total || 0,
        page: page + 1,
        finished: incoming.length < PAGE_SIZE
      })
    } catch (e) {
      this.setData({ finished: true })
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 进入权威信息详情（P3-33：原先只能看列表截断内容） */
  goDetail(e) {
    wx.navigateTo({ url: `/pages/authority-detail/authority-detail?id=${e.currentTarget.dataset.id}` })
  }
})
