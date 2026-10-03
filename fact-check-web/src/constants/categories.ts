/**
 * 消息分类（P3-39）
 *
 * 原先三个页面各自硬编码一份：DetectView / HistoryView 是 6 类（含"其他"），
 * AuthorityView 只有 5 类——同一个平台里分类列表不一致，用户在不同页面看到不同选项。
 * 现在统一从这里取，避免再次漂移。
 *
 * 与后端保持一致：不传分类时后端按 `其他` 兜底（`DetectServiceImpl.DEFAULT_CATEGORY`）。
 */
export const CATEGORIES = ['其他', '健康', '社会', '财经', '科技', '政策'] as const

export type Category = (typeof CATEGORIES)[number]
