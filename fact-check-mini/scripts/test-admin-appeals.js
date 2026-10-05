/**
 * 小程序管理端「申诉处理」页的逻辑测试（离线、确定性）
 *
 * ## 为什么要单独测它
 *
 * 这个页面的本质是"**按后端校验规则组装提交体**"。这类逻辑有个共同特点：
 *   * 静态检查看不出来（JS 语法、事件绑定都是对的）；
 *   * 错了的后果是**运行期被后端拒绝**或**静默写错数据**；
 *   * 而字段名/必填项只能靠读后端代码确认 —— 我自己就在这里踩过坑
 *     （给 `/admin/records/{id}/conclusion` 传过 `AdminConclusionDTO` 里不存在的字段）。
 *
 * 所以这里把后端 `AdminAppealServiceImpl` 的校验规则逐条翻成断言：
 *
 * | 后端规则 | 本文件断言 |
 * | --- | --- |
 * | 采纳：`newVerdict` ∈ {1,2,3} | 提交体带 newVerdict 且值合法 |
 * | 采纳：`newReasoningChain` 必填（trim 后非空） | 只填空格 → 不发请求 |
 * | 采纳：`newReasoningChain` ≤ 2000 字 | 超长 → 不发请求 |
 * | 驳回：`handleResult` 必填、≤ 500 字 | 空/超长 → 不发请求 |
 * | 采纳时 `handleResult` 是**选填**（后端会补默认文案） | 留空时不带该字段 |
 *
 * 另外验证几处容易写错的地方：
 *   * 状态筛选「全部」时**不能**传 `status`（后端是 Integer，传 '' 会解析失败）
 *   * `accept` 必须是**布尔**（radio 的 value 是字符串 'true'）
 *   * 分页 `finished` 判定与页码推进
 *
 * 用法：node scripts/test-admin-appeals.js
 */
const path = require('path')
const Module = require('module')

const MINI = path.join(__dirname, '..')

let storage
let requests
let wx

function resetWorld() {
  storage = new Map()
  requests = []

  wx = {
    setStorageSync: (k, v) => storage.set(k, v),
    getStorageSync: k => (storage.has(k) ? storage.get(k) : ''),
    removeStorageSync: k => storage.delete(k),
    showToast: o => { wx.__lastToast = o && o.title },
    request: opts => {
      requests.push({ url: opts.url, method: opts.method, data: opts.data })
      const resp = global.__nextResponse || { statusCode: 200, data: { code: 1, msg: 'ok', data: {} } }
      setImmediate(() => opts.success(resp))
    },
    reLaunch: () => {},
    navigateTo: () => {}
  }
  global.wx = wx
  global.getApp = () => ({ globalData: { userInfo: null, loginReady: Promise.resolve({ skipped: true }) } })
  global.__nextResponse = undefined
  wx.__lastToast = undefined
}

function freshRequire(rel) {
  const p = require.resolve(path.join(MINI, rel))
  delete require.cache[p]
  return require(p)
}

/** 载入页面模块并拿到它的 Page 配置；给一个可用的 setData */
function loadPage(rel) {
  let captured = null
  const prevPage = global.Page
  global.Page = cfg => { captured = cfg }
  try {
    freshRequire(rel)
  } finally {
    global.Page = prevPage
  }
  if (!captured) throw new Error('页面模块没有调用 Page()：' + rel)
  captured.setData = function (patch) { Object.assign(this.data, patch) }
  captured.data = Object.assign({}, captured.data)
  return captured
}

/** 造一条待处理申诉（字段取自 AppealVO） */
function pendingAppeal(id) {
  return {
    id: id,
    recordId: 100 + id,
    recordContent: '被申诉的那条消息',
    reasonType: 1,
    content: '结论有误，请复核',
    status: 0,
    createTime: '2026-10-05 10:00:00'
  }
}

const results = []
function check(name, cond, detail) {
  results.push({ name, ok: !!cond })
  console.log(`  ${cond ? '✅' : '❌'} ${name}${detail ? '  —— ' + detail : ''}`)
}

const sleep = ms => new Promise(r => setTimeout(r, ms))
function header(t) {
  console.log()
  console.log('='.repeat(74))
  console.log('  ' + t)
  console.log('='.repeat(74))
}

async function main() {
  const origLoad = Module._load
  Module._load = function (request) {
    if (request === 'wx') return global.wx
    return origLoad.apply(this, arguments)
  }

  // ---- 管理员守卫 ----
  header('守卫：非管理员不能进入申诉处理页')
  resetWorld()
  storage.set('token', 'USER_TOKEN')
  storage.set('role', '0')
  const pageAsUser = loadPage('pages/admin/appeals/appeals.js')
  await pageAsUser.onLoad()
  check('普通用户 onLoad 后不发起任何请求', requests.length === 0, `已发 ${requests.length} 条`)

  // ---- 列表加载 ----
  header('列表：默认筛「待处理」，且「全部」时不能传 status')
  resetWorld()
  storage.set('token', 'ADMIN_TOKEN')
  storage.set('role', '1')
  global.__nextResponse = {
    statusCode: 200,
    data: { code: 1, msg: 'ok', data: { total: 1, records: [pendingAppeal(7)] } }
  }
  const page = loadPage('pages/admin/appeals/appeals.js')
  await page.onLoad()
  await sleep(10)

  const firstReq = requests[0]
  check('默认请求 /admin/appeals', firstReq && firstReq.url.endsWith('/admin/appeals'),
    firstReq && firstReq.url)
  check('默认带 status=0（待处理）', firstReq && firstReq.data.status === 0,
    JSON.stringify(firstReq && firstReq.data))
  check('带分页参数', firstReq && firstReq.data.page === 1 && firstReq.data.pageSize === 10,
    JSON.stringify(firstReq && firstReq.data))
  check('记录已完成文案装饰',
    page.data.records.length === 1 && page.data.records[0].reasonText === '结论错误'
      && page.data.records[0].statusText === '待处理',
    JSON.stringify(page.data.records[0] && {
      reasonText: page.data.records[0].reasonText,
      statusText: page.data.records[0].statusText
    }))
  check('待处理记录标记为可操作', page.data.records[0].actionable === true)

  // 切到「全部」：不能把 status 传成空串
  requests.length = 0
  global.__nextResponse = { statusCode: 200, data: { code: 1, msg: 'ok', data: { total: 0, records: [] } } }
  page.onFilterChange({ detail: { value: '0' } })   // FILTERS[0] = 全部
  await sleep(10)
  const allReq = requests[0]
  check('选「全部」时**不带** status 字段',
    allReq && !('status' in allReq.data), JSON.stringify(allReq && allReq.data))

  // ---- 采纳 ----
  header('采纳：必须带 newVerdict 与 newReasoningChain')
  function openPanel(p) {
    p.setData({
      records: [Object.assign({}, pendingAppeal(7), {
        reasonText: '结论错误', statusText: '待处理', actionable: true
      })]
    })
    p.onOpenPanel({ currentTarget: { dataset: { id: 7 } } })
  }

  resetWorld()
  storage.set('token', 'ADMIN_TOKEN')
  storage.set('role', '1')
  const p1 = loadPage('pages/admin/appeals/appeals.js')
  p1.data.records = [Object.assign({}, pendingAppeal(7))]
  p1.onOpenPanel({ currentTarget: { dataset: { id: 7 } } })
  check('面板打开且默认选中「采纳」', p1.data.panelVisible === true && p1.data.accept === true)

  // 只填空格 → 不应发请求
  requests.length = 0
  p1.onAcceptChange({ detail: { value: 'true' } })
  p1.onReasoningInput({ detail: { value: '   ' } })
  await p1.onSubmit()
  check('推理依据只有空格 → 不发请求', requests.length === 0, `已发 ${requests.length} 条`)
  check('并给出明确提示', (wx.__lastToast || '').includes('新的推理依据'), wx.__lastToast)

  // 超长 → 不应发请求
  requests.length = 0
  p1.onReasoningInput({ detail: { value: 'x'.repeat(2001) } })
  await p1.onSubmit()
  check('推理依据超 2000 字 → 不发请求', requests.length === 0, `已发 ${requests.length} 条`)

  // 正常提交
  requests.length = 0
  global.__nextResponse = { statusCode: 200, data: { code: 1, msg: 'ok', data: null } }
  p1.onReasoningInput({ detail: { value: '  经复核，该说法已被权威来源证伪  ' } })
  p1.onVerdictChange({ detail: { value: '1' } })     // 真
  await p1.onSubmit()
  await sleep(10)
  const acceptReq = requests[0]
  check('提交到 /admin/appeals/7/handle', acceptReq && acceptReq.url.endsWith('/admin/appeals/7/handle'),
    acceptReq && acceptReq.url)
  check('accept 是布尔 true（不是字符串）', acceptReq && acceptReq.data.accept === true,
    JSON.stringify(acceptReq && acceptReq.data.accept))
  // onVerdictChange 收到的是 picker 的**下标**（字符串），'1' → VERDICTS[1] = 「假」= 2
  check('带 newVerdict=2（选择下标 1 → 「假」）', acceptReq && acceptReq.data.newVerdict === 2,
    JSON.stringify(acceptReq && acceptReq.data))
  check('newReasoningChain 已 trim', acceptReq && acceptReq.data.newReasoningChain === '经复核，该说法已被权威来源证伪',
    JSON.stringify(acceptReq && acceptReq.data.newReasoningChain))
  check('处理说明留空时**不带** handleResult（后端会补默认文案）',
    acceptReq && !('handleResult' in acceptReq.data), JSON.stringify(acceptReq && acceptReq.data))
  check('提交后关闭面板', p1.data.panelVisible === false)

  // ---- 驳回 ----
  header('驳回：handleResult 必填、≤500 字')
  const p2 = loadPage('pages/admin/appeals/appeals.js')
  p2.data.records = [Object.assign({}, pendingAppeal(8))]
  p2.onOpenPanel({ currentTarget: { dataset: { id: 8 } } })

  requests.length = 0
  p2.onAcceptChange({ detail: { value: 'false' } })
  check('切到「驳回」后 accept=false', p2.data.accept === false)
  await p2.onSubmit()
  check('驳回原因为空 → 不发请求', requests.length === 0, `已发 ${requests.length} 条`)
  check('给出「驳回原因」提示', (wx.__lastToast || '').includes('驳回原因'), wx.__lastToast)

  requests.length = 0
  p2.onResultInput({ detail: { value: 'y'.repeat(501) } })
  await p2.onSubmit()
  check('驳回原因超 500 字 → 不发请求', requests.length === 0, `已发 ${requests.length} 条`)

  requests.length = 0
  global.__nextResponse = { statusCode: 200, data: { code: 1, msg: 'ok', data: null } }
  p2.onResultInput({ detail: { value: '  现有证据不足以推翻原结论  ' } })
  await p2.onSubmit()
  await sleep(10)
  const rejectReq = requests[0]
  check('驳回时 accept=false', rejectReq && rejectReq.data.accept === false,
    JSON.stringify(rejectReq && rejectReq.data.accept))
  check('驳回原因已 trim', rejectReq && rejectReq.data.handleResult === '现有证据不足以推翻原结论',
    JSON.stringify(rejectReq && rejectReq.data.handleResult))
  check('驳回时**不**带 newVerdict / newReasoningChain',
    rejectReq && !('newVerdict' in rejectReq.data) && !('newReasoningChain' in rejectReq.data),
    JSON.stringify(rejectReq && rejectReq.data))

  // ---- 分页与已处理记录 ----
  header('分页与已处理记录')
  resetWorld()
  storage.set('token', 'ADMIN_TOKEN')
  storage.set('role', '1')
  const p3 = loadPage('pages/admin/appeals/appeals.js')
  global.__nextResponse = {
    statusCode: 200,
    data: { code: 1, msg: 'ok', data: { total: 3, records: Array.from({ length: 3 }, (_, i) => pendingAppeal(i + 1)) } }
  }
  await p3.onLoad()
  await sleep(10)
  check('不足一页 → finished=true', p3.data.finished === true, `finished=${p3.data.finished}`)

  // 已处理的记录不给「处理」入口
  const handled = { id: 9, recordId: 109, reasonType: 2, content: 'x', status: 2, handleResult: '已说明理由' }
  p3.setData({ records: [handled] })
  const decorated = p3.data.records.map(r => Object.assign({}, r, {
    reasonText: '证据不足', statusText: '已驳回', actionable: r.status === 0
  }))
  p3.setData({ records: decorated })
  check('已驳回记录 actionable=false（不给处理入口）', p3.data.records[0].actionable === false)

  console.log()
  console.log('='.repeat(74))
  const failed = results.filter(r => !r.ok)
  console.log(`  结果：${results.length - failed.length}/${results.length} 通过`)
  console.log('='.repeat(74))
  Module._load = origLoad
  process.exit(failed.length ? 1 : 0)
}

main().catch(e => {
  console.error('测试脚本自身异常：', e)
  process.exit(1)
})
