import request from '@/utils/request'

/** 发起申诉 */
export const submitAppeal = (data: any) => request.post('/appeal', data)

/** 我的申诉 */
export const pageMyAppeals = (params: any) => request.get('/appeal/my', { params })
