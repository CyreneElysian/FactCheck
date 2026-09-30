const { get, put } = require('../../utils/request')

Page({
  data: { form: { nickname: '', avatar: '' } },
  onLoad() { this.load() },
  async load() {
    try {
      const res = await get('/user/profile')
      this.setData({ form: { nickname: res.data.nickname || '', avatar: res.data.avatar || '' } })
    } catch (e) {}
  },
  onNickname(e) { this.setData({ 'form.nickname': e.detail.value }) },
  async onSave() {
    await put('/user/profile', this.data.form)
    wx.showToast({ title: '已保存', icon: 'success' })
  }
})
