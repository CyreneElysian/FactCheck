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
    appealBusy: false
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
      this.apply(res.data || {})
    } catch (e) {
      // 401 / 网络异常已由 request 层提示
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 把后端返回的记录整理成可直接渲染的数据 */
  apply(record) {
    const evidenceList = (record.evidenceList || []).map(item => {
      const credibility = Number(item.sourceCredibility)
      const relevance = Number(item.relevanceScore)
      return {
        id: item.id,
        title: item.title || '(无标题)',
        url: item.url || '',
        snippet: item.snippet || '',
        source: item.source || '',
        sourceType: item.sourceType,
        // 可信度 / 相关度格式化为百分比；缺失时显示 '-'
        credibilityText: isNaN(credibility) ? '-' : Math.round(credibility * 100) + '%',
        relevanceText: isNaN(relevance) ? '-' : Math.round(relevance * 100) + '%',
        // 高可信来源（≥0.9）给个"权威"标记，便于一眼区分
        isAuthoritative: !isNaN(credibility) && credibility >= 0.9
      }
    })

    this.setData({
      record,
      statusText: STATUS_TEXT[record.status] || '未知状态',
      verdictText: VERDICT_TEXT[record.verdict] || '待诊断',
      verdictClass: VERDICT_CLASS[record.verdict] || 'verdict-unknown',
      verdictSourceText: VERDICT_SOURCE_TEXT[record.verdictSource] || '',
      confidenceText: record.confidence === null || record.confidence === undefined
        ? ''
        : Math.round(Number(record.confidence) * 100) + '%',
      evidenceList,
      favorited: !!record.favorited,
      myRating: record.myRating === undefined ? null : record.myRating
    })

    // 检测中（status=0）时自行轮询，出结果后自动停止
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
        const res = await get(`/detect/record/${this.data.id}`)
        this.apply(res.data || {})
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
