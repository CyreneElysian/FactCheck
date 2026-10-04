const { get, post, del } = require('../../utils/request')

const VERDICT_TEXT = { 1: '真', 2: '假', 3: '无法判定' }
const VERDICT_CLASS = { 1: 'verdict-true', 2: 'verdict-false', 3: 'verdict-unknown' }
const STATUS_TEXT = { 0: '检测中', 1: '待人工诊断', 2: '已完成', 3: '检测失败' }
const VERDICT_SOURCE_TEXT = { 1: 'AI 判定', 2: '人工判定' }

/** 申诉类型（与 t_appeal.reason_type 注释一致） */
const REASON_OPTIONS = [
  { value: 1, label: '结论错误' },
  { value: 2, label: '证据不足' },
  { value: 3, label: '来源不可信' },
  { value: 4, label: '其他' }
]

/** 检测中的轮询间隔（与首页一致） */
const POLL_INTERVAL = 2000

/**
 * 把后端返回的 0~1 比例安全地转成数字；**无数据返回 null**
 *
 * 为什么不能用 Number()：`Number(null)` 与 `Number('')` 都等于 0，
 * 会把"没有这个数据"误判成"比值就是 0"（显示成 0%）。管理员补充的证据
 * 刻意留空可信度/相关度，必须显示 '-' 而不是 0%。
 */
function toRatio(value) {
  if (value === null || value === undefined) return null
  if (typeof value === 'string' && value.trim() === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

Page({
  data: {
    id: null,
    loading: true,
    statusText: '',
    verdictText: '',
    verdictClass: 'verdict-unknown',
    verdictSourceText: '',
    confidenceText: '',
    /** 证据列表（已在前端补好展示用字段，WXML 不做复杂表达式） */
    evidenceList: [],
    /** 已收藏态（后端详情接口返回） */
    favorited: false,
    /** 我的评分：1 有帮助 / 0 无帮助 / null 未评（后端详情接口返回） */
    myRating: null,
    favoriteBusy: false,
    ratingBusy: false,
    showAppeal: false,
    reasonOptions: REASON_OPTIONS,
    reasonIndex: 0,
    appealContent: '',
    appealBusy: false,
    /**
     * 只读模式（P3-34）
     *
     * 从热度榜点进来的记录大多不属于当前用户，走的是公开接口（/hot/{id}），
     * 此时隐藏收藏/评分/申诉，只保留浏览与分享。
     */
    readonly: false
  },

  onLoad(options) {
    const id = options && options.id
    if (!id) {
      wx.showToast({ title: '缺少记录 ID', icon: 'none' })
      return
    }
    // 分享链接可能被未登录用户打开：先引导登录（登录后回到本页）
    if (!wx.getStorageSync('token')) {
      wx.navigateTo({ url: '/pages/login/login' })
    }
    // 允许"分享到朋友圈"（需要显式声明菜单项）
    if (wx.showShareMenu) {
      wx.showShareMenu({ withShareTicket: true, menus: ['shareAppMessage', 'shareTimeline'] })
    }
    this.setData({ id })
    this.load()
  },

  onUnload() {
    this.stopPolling()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    try {
      const res = await get(`/detect/record/${this.data.id}`)
      this.apply(res.data || {}, false)
      return
    } catch (e) {
      // 只有"后端明确应答但这条记录不可读"才回落到公开接口；
      // 网络异常 / 服务端 5xx 原样报错，避免把真实故障伪装成"该消息因热度公开"。
      //
      // 说明 request.js 的 reject 载荷有两种形态：
      //   - 网络层失败：wx.request 的 err（无 statusCode）→ 提示网络异常
      //   - 后端业务失败：body{code,msg}（无 statusCode）→ 可以回落
      //   - 401/非 200：res{statusCode,...} → 5xx 直接报错，其余回落
      if (e && e.statusCode && e.statusCode >= 500) {
        this.setData({ loading: false })
        wx.showToast({ title: '服务暂时不可用，请稍后重试', icon: 'none' })
        return
      }
      if (e && !e.statusCode && e.errMsg !== undefined && e.code === undefined) {
        this.setData({ loading: false })
        wx.showToast({ title: '网络异常，请检查网络后重试', icon: 'none' })
        return
      }
    }
    try {
      const res = await get(`/hot/${this.data.id}`)
      this.apply(res.data || {}, true)
      wx.showToast({ title: '该消息因热度公开，仅可查看', icon: 'none' })
    } catch (e) {
      wx.showToast({ title: '记录不存在或已不在热度榜中', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 把后端返回的记录整理成可直接渲染的数据 */
  apply(record, readonly) {
    const evidenceList = (record.evidenceList || []).map(item => {
      const credibility = toRatio(item.sourceCredibility)
      const relevance = toRatio(item.relevanceScore)
      return {
        id: item.id,
        title: item.title || '(无标题)',
        url: item.url || '',
        snippet: item.snippet || '',
        source: item.source || '',
        sourceType: item.sourceType,
        // 可信度 / 相关度格式化为百分比；**缺失时显示 '-'**。
        // 注意不能用 isNaN(Number(x)) 判空：Number(null) 与 Number('') 都是 0，
        // 会把"无数据"显示成 '0%' —— 管理员补充的证据刻意留空这两项，显示 0% 是误导。
        credibilityText: credibility === null ? '-' : Math.round(credibility * 100) + '%',
        relevanceText: relevance === null ? '-' : Math.round(relevance * 100) + '%',
        // 高可信来源（≥0.9）给个"权威"标记，便于一眼区分
        isAuthoritative: credibility !== null && credibility >= 0.9
      }
    })

    this.setData({
      record,
      readonly: !!readonly,
      statusText: STATUS_TEXT[record.status] || '未知状态',
      verdictText: VERDICT_TEXT[record.verdict] || '待诊断',
      verdictClass: VERDICT_CLASS[record.verdict] || 'verdict-unknown',
      verdictSourceText: VERDICT_SOURCE_TEXT[record.verdictSource] || '',
      confidenceText: toRatio(record.confidence) === null
        ? ''
        : Math.round(Number(record.confidence) * 100) + '%',
      evidenceList,
      favorited: !!record.favorited,
      myRating: record.myRating === undefined ? null : record.myRating
    })

    // 检测中（status=0）时自行轮询，出结果后自动停止。
    // 记录只读（从热度榜进来的他人记录）时必须轮询 /hot/{id}：
    // 详情接口只允许归属者查看，打过去必然 403，轮询会首次就自我终止、页面永不更新。
    this._readonly = !!readonly
    if (record.status === 0) {
      this.startPolling()
    } else {
      this.stopPolling()
    }
  },

  startPolling() {
    if (this._timer) return
    this._timer = setInterval(async () => {
      try {
        const path = this._readonly ? `/hot/${this.data.id}` : `/detect/record/${this.data.id}`
        const res = await get(path)
        this.apply(res.data || {}, this._readonly)
      } catch (e) {
        this.stopPolling()
      }
    }, POLL_INTERVAL)
  },

  stopPolling() {
    if (this._timer) {
      clearInterval(this._timer)
      this._timer = null
    }
  },

  /** 收藏 / 取消收藏（后端详情接口返回 favorited，据此切换） */
  async onToggleFavorite() {
    if (this.data.favoriteBusy) return
    const favorited = this.data.favorited
    this.setData({ favoriteBusy: true })
    try {
      if (favorited) {
        await del(`/favorite/${this.data.id}`)
        this.setData({ favorited: false })
        wx.showToast({ title: '已取消收藏', icon: 'none' })
      } else {
        await post(`/favorite/${this.data.id}`)
        this.setData({ favorited: true })
        wx.showToast({ title: '已收藏', icon: 'none' })
      }
    } catch (e) {
      // 失败提示已由 request 层给出，状态保持原样
    } finally {
      this.setData({ favoriteBusy: false })
    }
  },

  /** 评分：有帮助(1) / 无帮助(0)，可重复提交（后端为 upsert） */
  async onRate(e) {
    if (this.data.ratingBusy) return
    const rating = Number(e.currentTarget.dataset.rating)
    this.setData({ ratingBusy: true })
    try {
      await post('/rating', { recordId: Number(this.data.id), rating })
      this.setData({ myRating: rating })
      wx.showToast({ title: '感谢反馈', icon: 'none' })
    } catch (err) {
      // 提示由 request 层给出
    } finally {
      this.setData({ ratingBusy: false })
    }
  },

  /**
   * 打开证据链接
   *
   * 小程序不允许直接打开外部网页（web-view 需业务域名备案），
   * 因此复制链接并提示用户到浏览器打开——这是小程序内的通用做法。
   */
  onOpenEvidence(e) {
    const url = e.currentTarget.dataset.url
    if (!url) {
      wx.showToast({ title: '该证据没有链接', icon: 'none' })
      return
    }
    wx.setClipboardData({
      data: url,
      success: () => wx.showToast({ title: '链接已复制，可在浏览器打开', icon: 'none' })
    })
  },

  onToggleAppeal() {
    this.setData({ showAppeal: !this.data.showAppeal })
  },

  onReasonChange(e) {
    this.setData({ reasonIndex: Number(e.detail.value) })
  },

  onAppealInput(e) {
    this.setData({ appealContent: e.detail.value })
  },

  async onSubmitAppeal() {
    const content = (this.data.appealContent || '').trim()
    if (!content) {
      wx.showToast({ title: '请填写申诉说明', icon: 'none' })
      return
    }
    if (this.data.appealBusy) return
    this.setData({ appealBusy: true })
    try {
      await post('/appeal', {
        recordId: Number(this.data.id),
        reasonType: this.data.reasonOptions[this.data.reasonIndex].value,
        content
      })
      wx.showToast({ title: '申诉已提交', icon: 'none' })
      this.setData({ showAppeal: false, appealContent: '', reasonIndex: 0 })
    } catch (e) {
      // 提示由 request 层给出
    } finally {
      this.setData({ appealBusy: false })
    }
  },

  goAuthority() {
    const info = this.data.record.authorityInfo
    if (info && info.id) {
      wx.navigateTo({ url: `/pages/authority-detail/authority-detail?id=${info.id}` })
    }
  },

  goAppeals() {
    wx.navigateTo({ url: '/pages/appeals/appeals' })
  },

  /** 微信原生分享：标题取消息摘要，路径带 recordId */
  onShareAppMessage() {
    const content = (this.data.record && this.data.record.content) || '事实检测'
    return {
      title: content.length > 30 ? content.slice(0, 30) + '…' : content,
      path: `/pages/detail/detail?id=${this.data.id}`
    }
  },

  /** 分享到朋友圈（打开后进入本详情页，未登录会引导登录） */
  onShareTimeline() {
    const content = (this.data.record && this.data.record.content) || '事实检测'
    return {
      title: content.length > 30 ? content.slice(0, 30) + '…' : content,
      query: `id=${this.data.id}`
    }
  }
})
