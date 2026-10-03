<template>
  <el-card style="max-width: 640px">
    <template #header>个人中心</template>
    <el-form label-width="80px">
      <el-form-item label="头像">
        <el-avatar :size="72" :src="form.avatar">{{ form.nickname.charAt(0) }}</el-avatar>
        <el-upload :show-file-list="false" :http-request="onUploadAvatar" style="margin-left: 12px">
          <el-button size="small">点击更换头像</el-button>
        </el-upload>
      </el-form-item>
      <el-form-item label="昵称">
        <el-input v-model="form.nickname" style="width: 240px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSave">保存资料</el-button>
      </el-form-item>
    </el-form>

    <el-divider />
    <el-form label-width="80px">
      <el-form-item label="原密码">
        <el-input v-model="pwd.oldPassword" type="password" show-password style="width: 240px" />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="pwd.newPassword" type="password" show-password style="width: 240px" />
      </el-form-item>
      <el-form-item>
        <el-button type="warning" @click="onUpdatePwd">修改密码</el-button>
      </el-form-item>
    </el-form>

    <el-divider />
    <el-button type="danger" plain @click="onCancel">注销账号</el-button>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelAccount, getProfile, updatePassword, updateProfile } from '@/api/auth'
import { uploadFile } from '@/api/detect'
import { useUserStore } from '@/store/user'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()
const form = reactive({ nickname: '', avatar: '' })
const pwd = reactive({ oldPassword: '', newPassword: '' })

onMounted(async () => {
  const res: any = await getProfile()
  form.nickname = res.data.nickname || ''
  form.avatar = res.data.avatar || ''
})

const onUploadAvatar = async (options: any) => {
  const res: any = await uploadFile(options.file)
  form.avatar = res.data
  ElMessage.success('头像已上传')
}

const onSave = async () => {
  await updateProfile({ nickname: form.nickname, avatar: form.avatar })
  // P3-39：同步到 store，顶部导航的头像/昵称立即刷新（原先要刷新页面才更新）
  userStore.updateUserInfo({ nickname: form.nickname, avatar: form.avatar })
  ElMessage.success('资料已保存')
}

const onUpdatePwd = async () => {
  if (!pwd.oldPassword || !pwd.newPassword) {
    ElMessage.warning('请输入原密码和新密码')
    return
  }
  await updatePassword(pwd)
  ElMessage.success('密码已修改，请重新登录')
  userStore.logout()
  router.push('/login')
}

const onCancel = async () => {
  await ElMessageBox.confirm('注销后不可登录，确定注销？', '提示', { type: 'warning' })
  await cancelAccount()
  ElMessage.success('账号已注销')
  userStore.logout()
  router.push('/login')
}
</script>
