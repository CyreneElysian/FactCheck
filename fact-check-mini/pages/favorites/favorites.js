const { get, del } = require('../../utils/request')
const { ensureLogin } = require('../../utils/auth-guard')

const PAGE_SIZE = 10

const VERDICT_TEXT = { 1: '真', 2: '假', 3: '无法判定' }
const VERDICT_CLASS = { 1: 'verdict-true', 2: 'verdict-false', 3: 'verdict-unknown' }

/** FavoriteVO 只带 verdict（无 status/verdictText），在前端补好展示字段 */
function decorate(item) {
  return Object.assign({}, item, {
    verdictText: VERDICT_TEXT[item.verdict] || '待诊断',
    verdictClass: VERDICT_CLASS[item.verdict] || 'verdict-unknown'
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
      const res = await get('/favorite/my', { page, pageSize: PAGE_SIZE })
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

  goDetail(e) {
    wx.navigateTo({ url: `/pages/detail/detail?id=${e.currentTarget.dataset.id}` })
  },

  async onRemove(e) {
    const id = e.currentTarget.dataset.id
    await del(`/favorite/${id}`)
    wx.showToast({ title: '已取消收藏', icon: 'none' })
    // 本地先移除，避免整页重载导致滚动位置丢失。
    // 注意 dataset 取出来的是**字符串**，而 item.recordId 是数字，
    // 直接 !== 比较会恒为 true（列表不刷新），因此统一转成字符串比较。
    this.setData({ records: this.data.records.filter(item => String(item.recordId) !== String(id)) })
  }
})
