const { get, put } = require('../../utils/request')
const { ensureLogin } = require('../../utils/auth-guard')

const PAGE_SIZE = 10

const TYPE_TEXT = {
  1: '检测完成',
  2: 'AI 判定',
  3: '人工诊断',
  4: '申诉结果',
  5: '系统通知',
  6: '检测失败'
}

function decorate(item) {
  return Object.assign({}, item, {
    typeText: TYPE_TEXT[item.type] || '通知',
    unread: item.isRead === 0
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

  /** 触底加载更多（P3-33：原先固定 20 条无分页） */
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
      const res = await get('/message/list', { page, pageSize: PAGE_SIZE })
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

  async onRead(e) {
    await put(`/message/${e.currentTarget.dataset.id}/read`)
    this.reload()
  },

  async onReadAll() {
    await put('/message/read-all')
    this.reload()
  },

  /** 关联记录（如"检测完成""人工诊断"）可直接跳到详情 */
  goRecord(e) {
    const id = e.currentTarget.dataset.id
    if (id) wx.navigateTo({ url: `/pages/detail/detail?id=${id}` })
  }
})
