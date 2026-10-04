/**
 * 展示层格式化纯函数
 *
 * 抽出来的原因：`percent()` 原先在 3 个页面里各写了一份（DetectView / HotDetailView /
 * PendingDetailView），而三份都漏了"空字符串"这一种输入
 * （`Number('') === 0`、`isNaN(0) === false`），于是后端返回 `''` 时会把
 * "无数据"显示成 `0%` —— 在人工诊断页里这等于给管理员一个错误的判断依据。
 *
 * 纯函数放这里便于单测，也避免以后再出现"改一处漏两处"。
 */

/** 百分比最大合法倍数：0~1 之外的输入一律视为脏数据 */
const isBlank = (value: unknown): boolean =>
  value === null || value === undefined || (typeof value === 'string' && value.trim() === '')

/**
 * 把 0~1 的比例格式化成百分比文本
 *
 * @param value 比例值（number 或数字字符串）
 * @param blank 无数据时显示的占位符，默认 '-'
 * @returns 形如 `93%`；无数据时为占位符
 */
export function percent(value: unknown, blank = '-'): string {
  if (isBlank(value)) return blank
  const num = Number(value)
  if (!Number.isFinite(num)) return blank
  return `${Math.round(num * 100)}%`
}

/**
 * 把 0~1 的比例格式化成百分比文本；无数据时返回空串
 *
 * 用于"没有数据就整行不显示"的场景（如 AI 置信度），与 {@link percent} 的区别
 * 仅在于占位符是空串而不是 `-`。
 */
export function percentOrEmpty(value: unknown): string {
  return percent(value, '')
}
