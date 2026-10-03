/**
 * WebSocket 工具：订阅检测结果
 *
 * 鉴权说明（P5-58）：浏览器无法给 WebSocket 设置自定义请求头，
 * 因此 token 只能通过查询参数传递；服务端握手时会校验 token 并比对记录归属
 * （只能订阅自己的记录）。
 */
export function connectDetectSocket(
  recordId: number,
  onMessage: (msg: any) => void
): () => void {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const token = localStorage.getItem('token') || ''
  const url = `${protocol}://${window.location.host}/api/ws/detect?recordId=${recordId}` +
    (token ? `&token=${encodeURIComponent(token)}` : '')
  const ws = new WebSocket(url)

  ws.onmessage = evt => {
    try {
      onMessage(JSON.parse(evt.data))
    } catch (e) {
      // 忽略非 JSON 消息
    }
  }
  ws.onerror = () => {
    // 连接失败时由轮询兜底
  }

  return () => {
    if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
      ws.close()
    }
  }
}
