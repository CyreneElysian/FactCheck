import { describe, it, expect } from 'vitest'
import { percent, percentOrEmpty } from '@/utils/format'

/**
 * 展示层格式化回归测试
 *
 * 钉住的缺陷：三处 `percent()` 原先都写成
 * `value === null || value === undefined || isNaN(Number(value)) ? '-' : ...`，
 * 而 `Number('') === 0`、`isNaN(0) === false` —— 于是后端返回**空串**时
 * 会显示 `0%` 而不是 `-`，把"无数据"误报成"0 可信度"。
 * 在人工诊断页里，这等于给管理员一个错误的判断依据。
 */
describe('percent', () => {
  it('正常比例转百分比', () => {
    expect(percent(0.93)).toBe('93%')
    expect(percent(0.955)).toBe('96%')
    expect(percent('0.8')).toBe('80%')
    expect(percent(0)).toBe('0%')
    expect(percent(1)).toBe('100%')
  })

  it('空值与空串都显示占位符，绝不显示 0%', () => {
    expect(percent(null)).toBe('-')
    expect(percent(undefined)).toBe('-')
    // 这一条是本次修复的核心
    expect(percent('')).toBe('-')
    expect(percent('   ')).toBe('-')
  })

  it('非数字字符串与 NaN/Infinity 显示占位符', () => {
    expect(percent('abc')).toBe('-')
    expect(percent(NaN)).toBe('-')
    expect(percent(Infinity)).toBe('-')
    expect(percent(-Infinity)).toBe('-')
  })

  it('占位符可自定义', () => {
    expect(percent(null, '—')).toBe('—')
    expect(percent('', '暂无')).toBe('暂无')
  })

  it('percentOrEmpty：无数据时返回空串（用于整行不显示的置信度）', () => {
    expect(percentOrEmpty(0.9)).toBe('90%')
    expect(percentOrEmpty(null)).toBe('')
    expect(percentOrEmpty('')).toBe('')
    expect(percentOrEmpty('abc')).toBe('')
  })
})
