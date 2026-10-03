import { describe, expect, it } from 'vitest'
import { WS_TOKEN_PARAM, buildDetectSocketUrl } from '@/utils/wsUrl'

/**
 * WebSocket 地址拼接测试（P4-47）
 *
 * 背景（P5-58）：握手需要 token，但浏览器无法给 WebSocket 设自定义头，只能走查询参数。
 * 这类拼接错误的表现是"连接被拒但看不出原因"，所以逐条锁定：
 * 协议、路径、recordId、token 编码、以及"没登录时不带 token"（由服务端拒绝 + 轮询兜底）。
 */
describe('buildDetectSocketUrl', () => {
  it('http 站点用 ws 协议', () => {
    expect(buildDetectSocketUrl(12, 'tk', 'http://localhost:8080'))
      .toBe('ws://localhost:8080/api/ws/detect?recordId=12&token=tk')
  })

  it('https 站点用 wss 协议', () => {
    expect(buildDetectSocketUrl(12, 'tk', 'https://example.com'))
      .toBe('wss://example.com/api/ws/detect?recordId=12&token=tk')
  })

  it('未登录时不附加 token 参数（服务端会拒绝握手，前端由轮询兜底）', () => {
    const url = buildDetectSocketUrl(7, null, 'http://localhost:8080')

    expect(url).toBe('ws://localhost:8080/api/ws/detect?recordId=7')
    expect(url).not.toContain(WS_TOKEN_PARAM)
  })

  it('token 中的特殊字符会被 URL 编码（JWT 含 . 与 - _ 以及可能的 =）', () => {
    const url = buildDetectSocketUrl(1, 'a.b-c_d==', 'http://localhost')

    expect(url).toContain('token=a.b-c_d%3D%3D')
  })

  it('recordId 会被编码（防拼接出额外参数）', () => {
    const url = buildDetectSocketUrl('1&admin=1', 'tk', 'http://localhost')

    expect(url).toContain('recordId=1%26admin%3D1')
    expect(url.split('&')).toHaveLength(2)
  })

  it('带端口与非默认路径的站点也能正确拼接', () => {
    expect(buildDetectSocketUrl(3, 'tk', 'https://example.com:8443'))
      .toBe('wss://example.com:8443/api/ws/detect?recordId=3&token=tk')
  })
})
