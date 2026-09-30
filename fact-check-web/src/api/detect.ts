import request from '@/utils/request'

/** 提交检测 */
export const submitDetect = (data: any) => request.post('/detect/submit', data)

/** 查询检测记录详情 */
export const getDetectRecord = (id: number | string) => request.get(`/detect/record/${id}`)

/** 分页查询我的检测记录 */
export const pageDetectRecords = (params: any) => request.get('/detect/records', { params })

/** 上传图片并 OCR 识别 */
export const recognizeImage = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/ocr/recognize', form, { headers: { 'Content-Type': 'multipart/form-data' } })
}

/** 查询检测记录的结论修订历史 */
export const getRecordRevisions = (id: number | string) => request.get(`/detect/record/${id}/revisions`)

/** 文件上传 */
export const uploadFile = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/common/upload', form, { headers: { 'Content-Type': 'multipart/form-data' } })
}
