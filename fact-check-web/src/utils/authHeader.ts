/**
 * 请求鉴权头构造（P4-47）
 *
 * 从 `request.ts` 的 axios 拦截器里抽出来的**纯函数**：不碰 localStorage、不依赖 axios，
 * 因此可以直接单测。抽出来的另一个好处是把"双 token 头"这条约定写在一处：
 * 管理端用 `token`，用户端用 `authentication`（后端两个拦截器分别读这两个头）。
 */
export const ADMIN_TOKEN_HEADER = 'token'
export const USER_TOKEN_HEADER = 'authentication'

/**
 * 按角色构造鉴权头
 *
 * @param token 登录令牌；为空表示未登录
 * @param role  角色，'1' 为管理员（后端 t_user.role 约定）
 * @returns 需要附加到请求上的头；未登录时返回空对象（调用方不应发送任何鉴权头）
 */
export function buildAuthHeader(
  token: string | null | undefined,
  role: string | number | null | undefined
): Record<string, string> {
  if (!token) {
    return {}
  }
  const isAdmin = String(role) === '1'
  return isAdmin ? { [ADMIN_TOKEN_HEADER]: token } : { [USER_TOKEN_HEADER]: token }
}
