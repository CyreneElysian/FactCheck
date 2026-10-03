import request from '@/utils/request'

/** 热门消息 Top10 */
export const getHotTop10 = () => request.get('/hot/top10')

/**
 * 公开查看热度榜中的记录详情（P3-34）
 *
 * 注意与 `getDetectRecord` 的区别：后者只允许查看**自己**的记录，
 * 热度榜里的记录多数不属于当前用户，因此必须走这个受限的公开接口
 * （服务端只放行当前在 Top10 内的记录，返回的也是脱敏视图）。
 */
export const getHotRecord = (recordId: number | string) => request.get(`/hot/${recordId}`)
