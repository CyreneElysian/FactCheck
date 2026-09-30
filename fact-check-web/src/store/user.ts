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
    }
  }
})
