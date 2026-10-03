/**
 * 检测结果 WebSocket 工具（P5-58 起需要携带 token）
 *
 * URL 拼接抽成**纯函数**便于单测：鉴权参数一旦拼错（少了 token、没编码），
 * 表现是"握手被拒但看不出原因"，靠手点很难发现。
 */

/** 握手时用于传递 token 的查询参数名（浏览器无法给 WebSocket 设自定义头） */
export const WS_TOKEN_PARAM = 'token'

/**
 * 拼接检测结果 WebSocket 地址
 *
 * @param recordId 记录 ID
 * @param token    登录令牌；为空时不附加 token（服务端会拒绝握手，由轮询兜底）
 * @param origin   形如 `https://example.com` 的站点地址
 * @returns 完整的 ws(s) 地址
 */
export function buildDetectSocketUrl(
  recordId: number | string,
  token: string | null | undefined,
  origin: string
): string {
  const protocol = origin.startsWith('https:') ? 'wss' : 'ws'
  // origin 形如 https://host[:port]：必须把协议**和** `//` 一起去掉，
  // 只去掉 `http:` 会拼出 `ws:////host`（四个斜杠）——这个 bug 由离线断言执行抓到。
  const host = origin.replace(/^https?:\/\//, '')
  const query = [`recordId=${encodeURIComponent(String(recordId))}`]
  if (token) {
    query.push(`${WS_TOKEN_PARAM}=${encodeURIComponent(token)}`)
  }
  return `${protocol}://${host}/api/ws/detect?${query.join('&')}`
}
