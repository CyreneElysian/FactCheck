const { get } = require('../../utils/request')
const { ensureLogin } = require('../../utils/auth-guard')

const PAGE_SIZE = 10

const REASON_TEXT = { 1: '结论错误', 2: '证据不足', 3: '来源不可信', 4: '其他' }
const STATUS_TEXT = { 0: '待处理', 1: '已采纳', 2: '已驳回' }

/** AppealVO 只有枚举值，在前端补好文案 */
function decorate(item) {
  const status = item.status
  return Object.assign({}, item, {
    reasonText: REASON_TEXT[item.reasonType] || '其他',
    statusText: STATUS_TEXT[status] || '未知',
    statusClass: status === 1 ? 'status-ok' : status === 2 ? 'status-no' : 'status-pending'
  })
}

Page({
  data: { records: [], page: 1, total: 0, loading: false, finished: false },

  async onShow() {
    if (!(await ensureLogin())) return
    this.reload()
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh())
  },

  /** 触底加载更多（P3-33） */
  onReachBottom() { this.loadMore() },

  reload() {
    this.setData({ page: 1, records: [], finished: false })
    return this.loadMore()
  },

  async loadMore() {
    if (this.data.loading || this.data.finished) return
    this.setData({ loading: true })
    const page = this.data.page
    try {
      const res = await get('/appeal/my', { page, pageSize: PAGE_SIZE })
      const data = res.data || {}
      const incoming = (data.records || []).map(decorate)
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

  goRecord(e) {
    const id = e.currentTarget.dataset.id
    if (id) wx.navigateTo({ url: `/pages/detail/detail?id=${id}` })
  }
})
