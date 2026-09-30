import request from '@/utils/request'

/** 权威信息分页 */
export const pageAuthority = (params: any) => request.get('/authority/list', { params })

/** 权威信息详情 */
export const getAuthority = (id: number | string) => request.get(`/authority/${id}`)
