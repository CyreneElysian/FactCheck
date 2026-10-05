const { get } = require('../../../utils/request')
const { ensureAdmin } = require('../../../utils/auth-guard')

Page({
  data: {
    list: [],
    loading: true,
    /** 加载失败原因（管理员最需要知道的是"为什么没数据"） */
    error: ''
  },

  async onLoad() {
    // 管理员守卫：非 tab 页面能被直接打开，必须自己挡一道。
    // 注意这只是 UX 层，真正的权限在 /admin/** 的拦截器上。
    if (!(await ensureAdmin())) return
    this.load()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  onShow() {
    // 从诊断详情页返回后刷新（状态可能已变化）
    if (this.data.list.length) {
      this.load()
    }
  },

  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const res = await get('/admin/pending/top10')
      const list = (res.data || []).map(item => Object.assign({}, item, {
        // 列表里只做轻量格式化，避免 WXML 里写复杂表达式
        requestCountText: item.requestCount ? `${item.requestCount} 次` : '—',
        evidenceText: item.evidenceCount == null ? '—' : `${item.evidenceCount} 条`,
        confidenceText: fmtPercent(item.confidence)
      }))
      this.setData({ list })
    } catch (e) {
      // 401/403 已由 request 层处理（跳登录页）；其余情况给出可读原因
      this.setData({ error: (e && e.msg) || '加载失败，请稍后重试', list: [] })
    } finally {
      this.setData({ loading: false })
    }
  },

  onOpen(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/admin/pending-detail/pending-detail?id=${id}` })
  }
})

/**
 * 比例（0~1）转百分比文本；**无数据返回 '-'**
 *
 * 为什么不用 Number()：`Number(null)` 与 `Number('')` 都是 0，
 * 会把"没有这个数据"显示成 0%（与 detail.js 的 toRatio 同一考量）。
 */
function fmtPercent(value) {
  if (value === null || value === undefined || value === '') return '—'
  const num = Number(value)
  if (!isFinite(num)) return '—'
  return `${Math.round(num * 100)}%`
}
