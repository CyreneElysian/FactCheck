<template>
  <el-card>
    <template #header>
      <div style="display: flex; justify-content: space-between">
        <span>账号管理</span>
        <el-button type="primary" @click="createVisible = true">创建管理员</el-button>
      </div>
    </template>
    <el-table v-loading="loading" :data="records">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="nickname" label="昵称" />
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-tag :type="row.role === 1 ? 'warning' : 'info'">{{
            row.role === 1 ? '管理员' : '用户'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : row.status === 0 ? 'danger' : 'info'">
            {{ row.status === 1 ? '正常' : row.status === 0 ? '禁用' : '已注销' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 1 && row.role !== 1"
            type="danger"
            link
            @click="onUpdateStatus(row, 0)"
            >禁用</el-button
          >
          <el-button v-else-if="row.status === 0" type="primary" link @click="onUpdateStatus(row, 1)"
            >启用</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      style="margin-top: 12px"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.pageSize"
      :current-page="query.page"
      @current-change="onPageChange"
    />

    <el-dialog v-model="createVisible" title="创建管理员" width="480px">
      <el-form label-width="80px">
        <el-form-item label="用户名"><el-input v-model="createForm.username" /></el-form-item>
        <el-form-item label="密码"
          ><el-input v-model="createForm.password" type="password" show-password
        /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="createForm.nickname" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createAdminAccount, pageAdminUsers, updateUserStatus } from '@/api/admin'

const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10 })
const createVisible = ref(false)
const createForm = reactive({ username: '', password: '', nickname: '' })

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageAdminUsers(query)
    records.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}
const onPageChange = (page: number) => {
  query.page = page
  load()
}
const onCreate = async () => {
  await createAdminAccount(createForm)
  ElMessage.success('管理员账号已创建')
  createVisible.value = false
  load()
}
const onUpdateStatus = async (row: any, status: number) => {
  await updateUserStatus(row.id, status)
  ElMessage.success(status === 1 ? '已启用' : '已禁用')
  load()
}
onMounted(load)
</script>
