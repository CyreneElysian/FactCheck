import request from '@/utils/request'

/** 收藏 */
export const addFavorite = (recordId: number | string) => request.post(`/favorite/${recordId}`)

/** 取消收藏 */
export const removeFavorite = (recordId: number | string) => request.delete(`/favorite/${recordId}`)

/** 我的收藏 */
export const pageMyFavorites = (params: any) => request.get('/favorite/my', { params })
