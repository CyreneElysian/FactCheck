<template>
  <el-card>
    <template #header>
      <div style="display: flex; justify-content: space-between">
        <span>权威信息管理</span>
        <el-button type="primary" @click="openAdd">新增</el-button>
      </div>
    </template>
    <el-table v-loading="loading" :data="records">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="title" label="标题" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{
            row.status === 1 ? '已发布' : row.status === 0 ? '草稿' : '已下线'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status !== 1" type="success" link @click="onStatus(row, 1)">发布</el-button>
          <el-button v-else type="warning" link @click="onStatus(row, 2)">下线</el-button>
          <el-button type="danger" link @click="onDelete(row.id)">删除</el-button>
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

    <el-dialog v-model="editVisible" :title="editId ? '编辑权威信息' : '新增权威信息'" width="560px">
      <el-form label-width="80px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="form.category" /></el-form-item>
        <el-form-item label="来源"><el-input v-model="form.source" /></el-form-item>
        <el-form-item label="原文链接"><el-input v-model="form.sourceUrl" /></el-form-item>
        <el-form-item label="正文"
          ><el-input v-model="form.content" type="textarea" :rows="5"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteAuthority,
  pageAdminAuthority,
  saveAuthority,
  updateAuthority,
  updateAuthorityStatus
} from '@/api/admin'

const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10 })
const editVisible = ref(false)
const editId = ref<number | null>(null)
const form = reactive({ title: '', category: '', source: '', sourceUrl: '', content: '' })

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageAdminAuthority(query)
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
const openAdd = () => {
  editId.value = null
  Object.assign(form, { title: '', category: '', source: '', sourceUrl: '', content: '' })
  editVisible.value = true
}
const openEdit = (row: any) => {
  editId.value = row.id
  Object.assign(form, row)
  editVisible.value = true
}
const onSave = async () => {
  if (editId.value) {
    await updateAuthority(editId.value, form)
    ElMessage.success('已更新')
  } else {
    await saveAuthority(form)
    ElMessage.success('已新增')
  }
  editVisible.value = false
  load()
}
const onStatus = async (row: any, status: number) => {
  await updateAuthorityStatus(row.id, status)
  ElMessage.success(status === 1 ? '已发布' : '已下线')
  load()
}
const onDelete = async (id: number) => {
  await ElMessageBox.confirm('确定删除？', '提示', { type: 'warning' })
  await deleteAuthority(id)
  ElMessage.success('已删除')
  load()
}
onMounted(load)
</script>
