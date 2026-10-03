import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { buildAuthHeader } from '@/utils/authHeader'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30000
})

request.interceptors.request.use(config => {
  // 双 token 头的映射逻辑抽到 utils/authHeader（纯函数，可单测）
  const headers = buildAuthHeader(localStorage.getItem('token'), localStorage.getItem('role'))
  Object.keys(headers).forEach(key => {
    config.headers[key] = headers[key]
  })
  return config
})

request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 1) {
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg || '请求失败'))
    }
    return res
  },
  error => {
    if (error.response?.status === 401) {
      ElMessage.error('请先登录')
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      localStorage.removeItem('userInfo')
      router.push('/login')
    } else {
      ElMessage.error(error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

export default request
