const { get, post } = require('../../../utils/request')
const { ensureAdmin } = require('../../../utils/auth-guard')

/**
 * 分类（与前端 constants/categories.ts 一致）—— 仅用于**展示**，不提交
 *
 * 注意：`AdminConclusionDTO` 只有 verdict / reasoningChain / reason 三个字段，
 * **不接受**修改正文与分类。所以这里不做"就地改分类"的输入，
 * 免得给出一个后端会静默忽略的假功能（编辑正文/分类请走 Web 管理端）。
 */
const CATEGORIES = ['其他', '健康', '社会', '财经', '科技', '政策']
/** 结论：与 t_fact_check_record.verdict 一致 */
const VERDICTS = [
  { value: 1, label: '真' },
  { value: 2, label: '假' },
  { value: 3, label: '无法判定' }
]
/** 推理依据长度上限：与后端 ValidationUtils.requireText(…, 2000) 对齐 */
const MAX_REASONING_LENGTH = 2000

Page({
  data: {
    id: null,
    loading: true,
    error: '',

    record: null,
    categoryText: '',
    verdicts: VERDICTS,
    verdictIndex: -1,
    reasoningChain: '',
    maxReasoningLength: MAX_REASONING_LENGTH,
    evidenceList: [],

    submitting: false
  },

  async onLoad(options) {
    if (!(await ensureAdmin())) return
    const id = options && options.id
    if (!id) {
      wx.showToast({ title: '缺少记录 ID', icon: 'none' })
      return
    }
    this.setData({ id })
    this.load()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const res = await get(`/admin/pending/${this.data.id}`)
      const r = res.data || {}
      this.setData({
        record: r,
        categoryText: r.category || '未分类',
        // 结论默认不选中（-1）：人工诊断必须由管理员明确选择，不能替他默认一个
        verdictIndex: -1,
        reasoningChain: '',
        evidenceList: (r.evidenceList || []).map(e => Object.assign({}, e, {
          credibilityText: toPercent(e.sourceCredibility),
          relevanceText: toPercent(e.relevanceScore)
        }))
      })
    } catch (e) {
      this.setData({ error: (e && e.msg) || '加载失败，请稍后重试' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onVerdictChange(e) { this.setData({ verdictIndex: Number(e.detail.value) }) },
  onReasoningInput(e) { this.setData({ reasoningChain: e.detail.value }) },

  /**
   * 提交人工诊断结论
   *
   * 对应 `POST /admin/records/{id}/conclusion`，请求体是 {@code AdminConclusionDTO}，
   * **只有三个字段**：`verdict` / `reasoningChain` / `reason`。
   *
   * 后端校验（AdminRecordServiceImpl.validateVerdictAndReasoning）：
   *   * `verdict` 必须 ∈ {1, 2, 3}；
   *   * `reasoningChain` 必填且 ≤ 2000 字；
   *   * 记录必须仍处于"待人工诊断"（status==1），否则报
   *     "该记录不在待诊断状态，无法人工诊断"。
   * 这里先做一遍前端校验，给出比后端更具体的提示。
   */
  async onSubmit() {
    const { id, verdictIndex, reasoningChain } = this.data

    if (verdictIndex < 0) {
      wx.showToast({ title: '请选择结论', icon: 'none' })
      return
    }
    const reasoning = reasoningChain.trim()
    if (!reasoning) {
      wx.showToast({ title: '请填写推理依据', icon: 'none' })
      return
    }
    if (reasoning.length > MAX_REASONING_LENGTH) {
      wx.showToast({ title: `推理依据不能超过 ${MAX_REASONING_LENGTH} 字`, icon: 'none' })
      return
    }
    if (this.data.submitting) return

    this.setData({ submitting: true })
    try {
      await post(`/admin/records/${id}/conclusion`, {
        verdict: VERDICTS[verdictIndex].value,
        reasoningChain: reasoning
      })
      wx.showToast({ title: '结论已提交', icon: 'success' })
      setTimeout(() => wx.navigateBack(), 1200)
    } catch (e) {
      // 提示由 request 层给出
    } finally {
      this.setData({ submitting: false })
    }
  }
})

/**
 * 比例（0~1）转百分比文本；**无数据返回 '-'**
 *
 * 不能用 Number()：`Number(null)` 与 `Number('')` 都是 0，
 * 会把"没有这个数据"显示成 0%（管理员补充的证据刻意留空可信度，
 * 必须显示 '-' 而不是 0%）。
 */
function toPercent(value) {
  if (value === null || value === undefined || value === '') return '—'
  const num = Number(value)
  if (!isFinite(num)) return '—'
  return `${Math.round(num * 100)}%`
}
