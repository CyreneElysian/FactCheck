/**
 * vitest 的最小替身（P4-47 的离线运行方案）
 *
 * 本机离线、装不了 vitest，但"没跑过的测试"等于没写。这里实现 describe/it/expect 的最小子集，
 * 配合 `scripts/spec-loader.mjs` 把 spec 文件里的 `import { describe, expect, it } from 'vitest'`
 * 重定向到本模块——于是**同一批 spec 既能被 vitest 跑，也能在零依赖下跑**，不需要维护两份断言。
 *
 * 只支持本项目实际用到的能力：toBe / toEqual / toContain / toHaveLength / toBeTruthy / toBeFalsy
 * 以及 `.not` 取反。装好 vitest 后请用 `npm run test:run`。
 */

let passed = 0
const failures = []

function stringify(value) {
  try {
    return JSON.stringify(value)
  } catch (e) {
    return String(value)
  }
}

function makeMatchers(actual, negated) {
  const assert = (ok, describeText) => {
    const finalOk = negated ? !ok : ok
    if (finalOk) {
      passed++
      return
    }
    const prefix = negated ? '不满足取反断言：' : '断言失败：'
    failures.push(`${prefix}${describeText}\n      实际值: ${stringify(actual)}`)
  }

  return {
    toBe: expected => assert(Object.is(actual, expected), `期望 ${stringify(expected)}`),
    toEqual: expected => assert(stringify(actual) === stringify(expected), `期望（深比较） ${stringify(expected)}`),
    toContain: expected => {
      const ok = typeof actual === 'string' ? actual.includes(expected) : Array.isArray(actual) && actual.includes(expected)
      assert(ok, `期望包含 ${stringify(expected)}`)
    },
    toHaveLength: expected => assert(actual != null && actual.length === expected, `期望长度为 ${expected}`),
    toBeTruthy: () => assert(!!actual, '期望为真值'),
    toBeFalsy: () => assert(!actual, '期望为假值'),
    toBeUndefined: () => assert(actual === undefined, '期望为 undefined'),
    toBeNull: () => assert(actual === null, '期望为 null')
  }
}

export function expect(actual) {
  return {
    ...makeMatchers(actual, false),
    not: makeMatchers(actual, true)
  }
}

export function describe(name, fn) {
  try {
    fn()
  } catch (e) {
    failures.push(`用例组「${name}」抛出异常：${e && e.stack ? e.stack : e}`)
  }
}

export function it(name, fn) {
  try {
    fn()
  } catch (e) {
    failures.push(`用例「${name}」抛出异常：${e && e.stack ? e.stack : e}`)
  }
}

export const test = it

/** 供运行器读取结果 */
export function report() {
  return { passed, failures }
}
