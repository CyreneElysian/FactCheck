/**
 * 登录守卫：统一的"未登录 → 去登录页"跳转
 *
 * ## 为什么需要它（真实 bug）
 *
 * 原先各页面各写各的，且用的是 `wx.navigateTo({ url: '/pages/login/login' })`。
 * 这有两个问题：
 *
 * 1. **`navigateTo` 把登录页压进页面栈，tabBar 被盖住** —— 用户看到的就是
 *    "点底部功能栏却跳到登录页，而且退不回去"。
 * 2. 登录页不是 tabBar 页，一旦压栈，用户按返回只能回来，形成"来回跳"的观感。
 *
 * 正确做法是 `wx.reLaunch`：它**会关闭所有页面**再打开目标页，
 * 既能跳到登录页（非 tab），也能从登录页跳回 tab 页，不会残留页面栈。
 *
 * ## 另一个真实 bug：登录页被注册成启动页
 *
 * `app.json` 的 `pages` 第一项即启动页，而当时写的是 `pages/login/login`。
 * 于是每次冷启动都先停在登录页 —— 即使本地已有 token，也要等 `onLoad` 里
 * 再 `switchTab` 跳走，表现为"启动时闪一下登录页"。
 * 现已把 `pages/index/index` 提为第一项，登录页不再是启动页。
 */

/** 登录页路径（唯一真源，避免各处拼错） */
const LOGIN_PAGE = '/pages/login/login'

/**
 * 当前是否已登录（本地有 token）
 */
function hasToken() {
  return !!wx.getStorageSync('token')
}

/**
 * 等 `app.onLaunch` 的静默登录结束。
 *
 * 必须 await：否则"静默登录还没跑完"会被当成"未登录"，
 * 出现刚启动就被踢到登录页的假象（这是 app.js 里 loginReady 存在的原由）。
 */
async function waitLoginReady() {
  const app = getApp()
  if (app && app.globalData && app.globalData.loginReady) {
    try {
      await app.globalData.loginReady
    } catch (e) {
      // 静默登录失败不是错误：交给调用方继续走"未登录"分支
      console.log('[auth-guard] 静默登录未成功，按未登录处理', e)
    }
  }
}

/**
 * 页面级守卫：未登录则跳到登录页。
 *
 * 用法（页面 onLoad 里）：
 *     const { ensureLogin } = require('../../utils/auth-guard')
 *     async onLoad() { if (!(await ensureLogin())) return; ... }
 *
 * @returns {Promise<boolean>} true = 已登录可继续渲染；false = 已跳登录页，调用方应直接 return
 */
async function ensureLogin() {
  await waitLoginReady()
  if (hasToken()) {
    return true
  }
  goLogin()
  return false
}

/**
 * 跳到登录页（清空页面栈）。
 *
 * 用 `reLaunch` 而不是 `navigateTo`/`redirectTo`：
 *   * `navigateTo` 只能跳非 tab 页，且会把登录页压栈，tabBar 被盖住；
 *   * `redirectTo` 同样不能跳 tab 页，登录成功后没法用它回 tab 页；
 *   * `reLaunch` 两者都能跳，且清空栈，不会残留。
 */
function goLogin() {
  console.log('[auth-guard] 未登录，reLaunch 到登录页')
  wx.reLaunch({ url: LOGIN_PAGE })
}

/**
 * 登录成功后回主界面（tab 页）
 */
function goHome() {
  wx.reLaunch({ url: '/pages/index/index' })
}

module.exports = { LOGIN_PAGE, hasToken, waitLoginReady, ensureLogin, goLogin, goHome }
