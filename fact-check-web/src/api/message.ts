import request from '@/utils/request'

/** 站内信列表 */
export const pageMessages = (params: any) => request.get('/message/list', { params })

/** 标记单条已读 */
export const readMessage = (id: number | string) => request.put(`/message/${id}/read`)

/** 全部已读 */
export const readAllMessages = () => request.put('/message/read-all')

/** 未读消息数 */
export const getUnreadCount = () => request.get('/message/unread-count')
