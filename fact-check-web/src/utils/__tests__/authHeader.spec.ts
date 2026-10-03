import { describe, expect, it } from 'vitest'
import { ADMIN_TOKEN_HEADER, USER_TOKEN_HEADER, buildAuthHeader } from '@/utils/authHeader'

/**
 * 鉴权头映射测试（P4-47）
 *
 * 锁定的是一条**越权边界**：管理端与用户端用的是两个不同的请求头，
 * 后端 `JwtTokenAdminInterceptor` 读 `token`、`JwtTokenUserInterceptor` 读 `authentication`。
 * 一旦头写反，用户拿到的 token 会被管理端拦截器当成空 → 401，
 * 或者更糟：用用户 token 冒充管理员（取决于后端实现）。这类错误在页面上只表现为"登录了但一直 401"。
 */
describe('buildAuthHeader', () => {
  it('未登录（无 token）时不发送任何鉴权头', () => {
    expect(buildAuthHeader(null, null)).toEqual({})
    expect(buildAuthHeader('', '1')).toEqual({})
    expect(buildAuthHeader(undefined, undefined)).toEqual({})
  })

  it('普通用户使用 authentication 头', () => {
    expect(buildAuthHeader('user-token', '0')).toEqual({ [USER_TOKEN_HEADER]: 'user-token' })
  })

  it('管理员使用 token 头', () => {
    expect(buildAuthHeader('admin-token', '1')).toEqual({ [ADMIN_TOKEN_HEADER]: 'admin-token' })
  })

  it('角色为数字 1 时同样按管理员处理（localStorage 可能存成数字字符串或数字）', () => {
    expect(buildAuthHeader('admin-token', 1)).toEqual({ [ADMIN_TOKEN_HEADER]: 'admin-token' })
  })

  it('角色缺失时按普通用户处理（不猜测管理员身份）', () => {
    expect(buildAuthHeader('some-token', null)).toEqual({ [USER_TOKEN_HEADER]: 'some-token' })
  })

  it('只返回一个头，绝不两个都发', () => {
    const adminHeader = buildAuthHeader('t', '1')
    const userHeader = buildAuthHeader('t', '0')

    expect(Object.keys(adminHeader)).toHaveLength(1)
    expect(Object.keys(userHeader)).toHaveLength(1)
  })
})
