import request from '@/utils/request'

/** 用户注册 */
export const register = (data: any) => request.post('/user/register', data)

/** 用户/管理员登录 */
export const login = (data: any) => request.post('/user/login', data)

/** 获取当前用户信息 */
export const getProfile = () => request.get('/user/profile')

/** 修改资料 */
export const updateProfile = (data: any) => request.put('/user/profile', data)

/** 修改密码 */
export const updatePassword = (data: any) => request.put('/user/password', data)

/** 注销账号 */
export const cancelAccount = () => request.post('/user/cancel')
