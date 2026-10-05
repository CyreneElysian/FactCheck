const { get } = require('../../../utils/request')
const { ensureAdmin } = require('../../../utils/auth-guard')

/**
 * 统计指标的展示元数据
 *
 * 放在前端而不是后端返回：后端 `StatsOverviewVO` 只给数字，
 * 卡片顺序与文案属于展示层的事。
 */
const METRICS = [
  { key: 'todayDetectCount', label: '今日检测', hint: '今日提交的检测总数' },
  { key: 'todayAiCount', label: 'AI 判定', hint: '由 AI 直接给出结论' },
  { key: 'todayManualCount', label: '人工判定', hint: '由管理员人工诊断' },
  { key: 'pendingCount', label: '待人工诊断', hint: 'AI 无法判定、等待处理' },
  { key: 'pendingAppealCount', label: '待处理申诉', hint: '用户提交的申诉' },
  { key: 'totalUserCount', label: '用户总数', hint: '累计注册用户' }
]

Page({
  data: {
    metrics: [],
    loading: true,
    error: '',
    /** 拉取时间，让管理员知道数据的新鲜度 */
    updatedAt: ''
  },

  async onLoad() {
    if (!(await ensureAdmin())) return
    this.load()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const res = await get('/admin/stats/overview')
      const d = res.data || {}
      this.setData({
        // 缺字段时显示 '—' 而不是 undefined，避免界面上出现空白
        metrics: METRICS.map(m => ({
          label: m.label,
          hint: m.hint,
          value: d[m.key] === null || d[m.key] === undefined ? '—' : String(d[m.key])
        })),
        updatedAt: formatNow()
      })
    } catch (e) {
      this.setData({ error: (e && e.msg) || '加载失败，请稍后重试', metrics: [] })
    } finally {
      this.setData({ loading: false })
    }
  }
})

/**
 * 当前时间 HH:mm:ss
 *
 * 手写补零而不用 `String.prototype.padStart`：后者依赖 ES2017，
 * 小程序基础库版本较低时可能不可用（这类兼容问题只在真机上暴露，很难提前发现）。
 */
function formatNow() {
  const d = new Date()
  const two = n => (n < 10 ? '0' + n : String(n))
  return `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`
}
