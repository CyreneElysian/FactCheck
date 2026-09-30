import request from '@/utils/request'

/** 热门消息 Top10 */
export const getHotTop10 = () => request.get('/hot/top10')
