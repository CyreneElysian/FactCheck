import { defineStore } from 'pinia'
import { login as loginApi } from '@/api/auth'

interface UserInfo {
  id: number
  username: string
  nickname: string
  avatar: string
  role: number
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    role: Number(localStorage.getItem('role') || 0),
    userInfo: JSON.parse(localStorage.getItem('userInfo') || 'null') as UserInfo | null
  }),
  actions: {
    async login(username: string, password: string) {
      const res: any = await loginApi({ username, password })
      const data = res.data
      this.token = data.token
      this.role = data.role
      this.userInfo = data
      localStorage.setItem('token', data.token)
      localStorage.setItem('role', String(data.role))
      localStorage.setItem('userInfo', JSON.stringify(data))
    },
    logout() {
      this.token = ''
      this.role = 0
      this.userInfo = null
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      localStorage.removeItem('userInfo')
    },
    /**
     * 局部更新用户信息（P3-39）
     *
     * 个人中心保存昵称/头像后原先只调接口、不更新 store，导致
     * 顶部导航的头像与昵称仍是登录时的旧值（刷新页面才好）。
     * 这里同时写回 localStorage，保证刷新后也一致。
     */
    updateUserInfo(partial: Partial<UserInfo>) {
      if (!this.userInfo) return
      this.userInfo = { ...this.userInfo, ...partial }
      localStorage.setItem('userInfo', JSON.stringify(this.userInfo))
    }
  }
})
