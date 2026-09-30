/**
 * WebSocket 工具：订阅检测结果
 */
export function connectDetectSocket(
  recordId: number,
  onMessage: (msg: any) => void
): () => void {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const ws = new WebSocket(`${protocol}://${window.location.host}/api/ws/detect?recordId=${recordId}`)

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
