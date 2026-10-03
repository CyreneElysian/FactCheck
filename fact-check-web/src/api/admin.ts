import request from '@/utils/request'

/** 待诊断 Top10 */
export const getPendingTop10 = () => request.get('/admin/pending/top10')

/** 待诊断详情 */
export const getPendingDetail = (id: number | string) => request.get(`/admin/pending/${id}`)

/** 转派 */
export const assignPending = (id: number | string, data: any) => request.put(`/admin/pending/${id}/assign`, data)

/** 人工诊断 */
export const submitConclusion = (id: number | string, data: any) => request.post(`/admin/records/${id}/conclusion`, data)

/** 修改结论 */
export const updateConclusion = (id: number | string, data: any) => request.put(`/admin/records/${id}/conclusion`, data)

/** 检测记录分页 */
export const pageAdminRecords = (params: any) => request.get('/admin/records', { params })

/** 申诉分页 */
export const pageAdminAppeals = (params: any) => request.get('/admin/appeals', { params })

/** 处理申诉 */
export const handleAppeal = (id: number | string, data: any) => request.post(`/admin/appeals/${id}/handle`, data)

/** 统计概览 */
export const getStatsOverview = () => request.get('/admin/stats/overview')

/** 操作日志 */
export const pageAdminLogs = (params: any) => request.get('/admin/stats/logs', { params })

/** 权威信息分页（管理端） */
export const pageAdminAuthority = (params: any) => request.get('/admin/authority/list', { params })

/** 新增权威信息 */
export const saveAuthority = (data: any) => request.post('/admin/authority', data)

/** 编辑权威信息 */
export const updateAuthority = (id: number | string, data: any) => request.put(`/admin/authority/${id}`, data)

/** 上/下线权威信息 */
export const updateAuthorityStatus = (id: number | string, status: number) =>
  request.put(`/admin/authority/${id}/status`, null, { params: { status } })

/** 删除权威信息 */
export const deleteAuthority = (id: number | string) => request.delete(`/admin/authority/${id}`)

/** 创建管理员账号 */
export const createAdminAccount = (data: any) => request.post('/admin/account', data)

/** 用户列表（管理端） */
export const pageAdminUsers = (params: any) => request.get('/admin/account/users', { params })

/** 启用/禁用用户 */
export const updateUserStatus = (id: number | string, status: number) =>
  request.put(`/admin/account/user/${id}/status`, { status })

/** 查询结论修订历史 */
export const getRecordRevisions = (id: number | string) => request.get(`/admin/records/${id}/revisions`)

/** 管理员简要列表（P3-35：诊断转派下拉框，替代手填 ID） */
export const listAdmins = () => request.get('/admin/account/admins')

/** 补充证据（P3-35） */
export const addEvidence = (recordId: number | string, data: any) =>
  request.post(`/admin/records/${recordId}/evidence`, data)

/** 删除证据（P3-35） */
export const removeEvidence = (recordId: number | string, evidenceId: number | string) =>
  request.delete(`/admin/records/${recordId}/evidence/${evidenceId}`)

/** 管理员个人中心 */
export const getAdminProfile = () => request.get('/admin/profile')

/** 修改管理员资料 */
export const updateAdminProfile = (data: any) => request.put('/admin/profile', data)

/** 修改管理员密码 */
export const updateAdminPassword = (data: any) => request.put('/admin/profile/password', data)
