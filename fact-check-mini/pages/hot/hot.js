const { get } = require('../../utils/request')

const VERDICT_TEXT = { 1: '真', 2: '假', 3: '无法判定' }
const VERDICT_CLASS = { 1: 'verdict-true', 2: 'verdict-false', 3: 'verdict-unknown' }

Page({
  data: { list: [], loading: true },

  onShow() { this.load() },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    try {
      const res = await get('/hot/top10')
      const list = (res.data || []).map(item => ({
        recordId: item.recordId,
        content: item.content,
        category: item.category,
        hotScore: item.hotScore,
        hitCount: item.hitCount,
        verdict: item.verdict,
        verdictText: VERDICT_TEXT[item.verdict] || '待诊断',
        verdictClass: VERDICT_CLASS[item.verdict] || 'verdict-unknown'
      }))
      this.setData({ list })
    } catch (e) {
      // 提示由 request 层给出
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 点击进入详情（P3-33：原先热度榜不可点） */
  goDetail(e) {
    wx.navigateTo({ url: `/pages/detail/detail?id=${e.currentTarget.dataset.id}` })
  },

  /** 分享按钮用 catchtap 阻止冒泡到整卡，因此需要一个占位处理函数 */
  noop() {},

  /**
   * 原生分享（P3-31）
   *
   * 列表项上的分享按钮会带 data-id / data-content，此时分享该条记录的详情页；
   * 右上角菜单分享则分享热度榜本身。
   */
  onShareAppMessage(e) {
    const ds = (e && e.target && e.target.dataset) || {}
    if (ds.id) {
      const content = ds.content || '事实检测'
      return {
        title: content.length > 30 ? content.slice(0, 30) + '…' : content,
        path: `/pages/detail/detail?id=${ds.id}`
      }
    }
    return { title: '事实检测 · 热门消息榜', path: '/pages/hot/hot' }
  },

  onShareTimeline() {
    return { title: '事实检测 · 热门消息榜' }
  }
})
