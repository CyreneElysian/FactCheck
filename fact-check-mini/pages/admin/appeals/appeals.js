const { get, post } = require('../../../utils/request')
const { ensureAdmin } = require('../../../utils/auth-guard')

const PAGE_SIZE = 10

/** 申诉类型（与 t_appeal.reason_type 一致） */
const REASON_TEXT = { 1: '结论错误', 2: '证据不足', 3: '来源不可信', 4: '其他' }
/** 申诉状态（与 t_appeal.status 一致） */
const STATUS_TEXT = { 0: '待处理', 1: '已采纳', 2: '已驳回' }

/** 状态筛选：'' 表示全部 */
const FILTERS = [
  { value: '', label: '全部' },
  { value: 0, label: '待处理' },
  { value: 1, label: '已采纳' },
  { value: 2, label: '已驳回' }
]

/** 结论选项（采纳申诉必须给出新结论） */
const VERDICTS = [
  { value: 1, label: '真' },
  { value: 2, label: '假' },
  { value: 3, label: '无法判定' }
]

/** 长度上限：与后端校验对齐（新推理依据 2000 字、驳回原因 VARCHAR(500)） */
const MAX_REASONING_LENGTH = 2000
const MAX_RESULT_LENGTH = 500

Page({
  data: {
    records: [],
    page: 1,
    total: 0,
    loading: false,
    finished: false,
    error: '',

    filters: FILTERS,
    filterIndex: 1,          // 默认「待处理」——管理员的日常入口
    statusValue: 0,

    // ---- 处理面板 ----
    panelVisible: false,
    current: null,
    accept: true,
    verdicts: VERDICTS,
    verdictIndex: 0,
    newReasoningChain: '',
    handleResult: '',
    maxReasoningLength: MAX_REASONING_LENGTH,
    maxResultLength: MAX_RESULT_LENGTH,
    submitting: false
  },

  async onLoad() {
    if (!(await ensureAdmin())) return
    this.reload()
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() { this.loadMore() },

  onFilterChange(e) {
    const idx = Number(e.detail.value)
    this.setData({ filterIndex: idx, statusValue: FILTERS[idx].value })
    this.reload()
  },

  reload() {
    this.setData({ page: 1, records: [], finished: false })
    return this.loadMore()
  },

  async loadMore() {
    if (this.data.loading || this.data.finished) return
    this.setData({ loading: true, error: '' })
    const page = this.data.page
    try {
      const query = { page, pageSize: PAGE_SIZE }
      // status 为空串表示"全部"，此时不能传该参数（后端是 Integer，传 '' 会解析失败）
      if (this.data.statusValue !== '') {
        query.status = this.data.statusValue
      }
      const res = await get('/admin/appeals', query)
      const data = res.data || {}
      const list = (data.records || []).map(decorate)
      this.setData({
        records: this.data.records.concat(list),
        total: data.total || 0,
        page: page + 1,
        finished: list.length < PAGE_SIZE
      })
    } catch (e) {
      this.setData({ error: (e && e.msg) || '加载失败，请稍后重试', finished: true })
    } finally {
      this.setData({ loading: false })
    }
  },

  // ---- 处理 ----

  onOpenPanel(e) {
    const id = Number(e.currentTarget.dataset.id)
    const current = this.data.records.find(r => r.id === id) || null
    this.setData({
      panelVisible: true,
      current,
      accept: true,
      verdictIndex: 0,
      newReasoningChain: '',
      handleResult: ''
    })
  },

  onClosePanel() {
    this.setData({ panelVisible: false, current: null })
  },

  /**
   * 空处理函数：面板用 `catchtap="noop"` 阻止点击冒泡到遮罩，
   * 否则点面板内部任意位置都会触发遮罩的关闭，表单没法填。
   */
  noop() {},

  onAcceptChange(e) {
    this.setData({ accept: e.detail.value === 'true' })
  },

  onVerdictChange(e) {
    this.setData({ verdictIndex: Number(e.detail.value) })
  },

  onResultInput(e) { this.setData({ handleResult: e.detail.value }) },
  onReasoningInput(e) { this.setData({ newReasoningChain: e.detail.value }) },

  /**
   * 提交处理结果
   *
   * 对应 `POST /admin/appeals/{id}/handle`，请求体是 {@code AdminAppealHandleDTO}：
   * `accept` / `handleResult` / `newVerdict` / `newReasoningChain`。
   *
   * 后端校验（AdminAppealServiceImpl）：
   *   * **采纳**：`newVerdict` 必须 ∈ {1,2,3}，`newReasoningChain` **必填**且 ≤2000 字；
   *     且关联记录必须已完成（status==2），否则报"该申诉关联的记录未完成，无法采纳修改"。
   *   * **驳回**：`handleResult`（驳回原因）**必填**且 ≤500 字。
   *   * 申诉必须仍为"待处理"，否则报"该申诉已处理，请勿重复操作"。
   */
  async onSubmit() {
    const { current, accept, verdictIndex, newReasoningChain, handleResult } = this.data
    if (!current || this.data.submitting) return

    const payload = { accept: accept }

    if (accept) {
      const reasoning = newReasoningChain.trim()
      if (!reasoning) {
        wx.showToast({ title: '采纳申诉必须填写新的推理依据', icon: 'none' })
        return
      }
      if (reasoning.length > MAX_REASONING_LENGTH) {
        wx.showToast({ title: `推理依据不能超过 ${MAX_REASONING_LENGTH} 字`, icon: 'none' })
        return
      }
      payload.newVerdict = VERDICTS[verdictIndex].value
      payload.newReasoningChain = reasoning
      // 处理说明选填：不填时后端会记为"申诉采纳，修改结论"
      if (handleResult.trim()) {
        payload.handleResult = handleResult.trim()
      }
    } else {
      const reason = handleResult.trim()
      if (!reason) {
        wx.showToast({ title: '驳回必须填写驳回原因', icon: 'none' })
        return
      }
      if (reason.length > MAX_RESULT_LENGTH) {
        wx.showToast({ title: `驳回原因不能超过 ${MAX_RESULT_LENGTH} 字`, icon: 'none' })
        return
      }
      payload.handleResult = reason
    }

    this.setData({ submitting: true })
    try {
      await post(`/admin/appeals/${current.id}/handle`, payload)
      wx.showToast({ title: accept ? '已采纳' : '已驳回', icon: 'success' })
      this.setData({ panelVisible: false, current: null })
      this.reload()
    } catch (e) {
      // 提示由 request 层给出（含并发冲突的"该申诉已处理"）
    } finally {
      this.setData({ submitting: false })
    }
  }
})

/** AppealVO 只有枚举值，在前端补好文案 */
function decorate(item) {
  const status = item.status
  return Object.assign({}, item, {
    reasonText: REASON_TEXT[item.reasonType] || '其他',
    statusText: STATUS_TEXT[status] || '未知',
    statusClass: status === 1 ? 'status-ok' : status === 2 ? 'status-no' : 'status-pending',
    // 只有待处理的才给处理入口，避免管理员点进去才发现不能处理
    actionable: status === 0
  })
}
