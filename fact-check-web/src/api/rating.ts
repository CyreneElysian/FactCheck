import request from '@/utils/request'

/** 提交/修改评分 */
export const submitRating = (data: any) => request.post('/rating', data)
