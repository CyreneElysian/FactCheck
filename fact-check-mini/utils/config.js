/**
 * 小程序接口地址配置（P3-41）
 *
 * 原实现把 `http://127.0.0.1:8080/api` 硬编码在这里，只能开发者工具本地调试，
 * 且**绕过了 nginx**（与 Web 端部署链路不一致）。现在按环境切换：
 *
 * - `local`：开发者工具直连本机 Java（需在详情里勾选"不校验合法域名"）
 * - `lan`  ：真机联调，填本机在局域网中的 IP
 * - `prod` ：生产，必须是 **HTTPS 域名**，并已加入微信后台的
 *            「开发管理 → 服务器域名」request / uploadFile 合法域名
 *
 * 切换方式：改下面的 `ENV` 即可。生产环境建议通过构建/上传时替换，
 * 不要把 127.0.0.1 带到线上。
 */
const ENV = 'local'

const BASE_URLS = {
  local: 'http://127.0.0.1:8080/api',
  // 真机联调示例（改成你电脑的局域网 IP）
  lan: 'http://192.168.1.100:8080/api',
  // 生产：走 nginx + HTTPS，域名需在微信后台备案白名单
  prod: 'https://your-domain.com/api'
}

function currentBaseUrl() {
  const url = BASE_URLS[ENV]
  if (!url) {
    console.error('[config] 未识别的 ENV =', ENV, '，回退 local')
    return BASE_URLS.local
  }
  return url
}

module.exports = {
  ENV,
  baseUrl: currentBaseUrl(),
  /** 供页面提示用：是否本地调试地址 */
  isLocal: ENV === 'local'
}
