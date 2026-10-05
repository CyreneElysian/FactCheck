const { get } = require('../../utils/request')
const { ensureLogin } = require('../../utils/auth-guard')

const PAGE_SIZE = 10

const VERDICT_TEXT = { 1: '真', 2: '假', 3: '无法判定' }
const VERDICT_CLASS = { 1: 'verdict-true', 2: 'verdict-false', 3: 'verdict-unknown' }

/**
 * 状态与结论的统一文案（P3-33）
 *
 * 修复的 bug：原模板用
 *   `verdict===1 ? 真 : verdict===2 ? 假 : status===1 ? 待人工 : 检测中`
 * 当 status=2（已完成）而 verdict 为空（例如人工诊断只写了结论说明、或数据异常）时会显示"检测中"，
 * 明明已经完成却看起来还在跑。现在按 status 优先判定。
 */
function decorate(item) {
  let text = '检测中'
  let cls = 'verdict-unknown'
  if (item.status === 3) {
    text = '检测失败'
    cls = 'verdict-false'
  } else if (item.status === 1) {
    text = '待人工诊断'
    cls = 'verdict-unknown'
  } else if (item.status === 2) {
    text = VERDICT_TEXT[item.verdict] || '已完成'
    cls = VERDICT_CLASS[item.verdict] || 'verdict-unknown'
  }
  return Object.assign({}, item, { verdictText: text, verdictClass: cls })
}

Page({
  data: { records: [], page: 1, total: 0, loading: false, finished: false },

  async onShow() {
    // 登录守卫必须在这里：冷启动时 app.onLaunch 的静默登录要一次网络往返，
    // 不等它就会在 token 写入 storage 之前发请求 → 不带鉴权头 → 后端 401 → 被踢到登录页
    if (!(await ensureLogin())) return
    // 每次进入都重新拉第一页（检测状态可能已变化）
    this.reload()
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh())
  },

  /** 触底加载下一页（P3-33：原先固定只取 20 条且无分页） */
  onReachBottom() {
    this.loadMore()
  },

  reload() {
    this.setData({ page: 1, records: [], finished: false })
    return this.loadMore()
  },

  async loadMore() {
    if (this.data.loading || this.data.finished) return
    this.setData({ loading: true })
    const page = this.data.page
    try {
      const res = await get('/detect/records', { page, pageSize: PAGE_SIZE })
      const data = res.data || {}
      const incoming = (data.records || []).map(decorate)
      const records = page === 1 ? incoming : this.data.records.concat(incoming)
      this.setData({
        records,
        total: data.total || records.length,
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
  }
})
