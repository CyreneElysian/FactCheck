<template>
  <el-card>
    <template #header>
      <div style="display: flex; justify-content: space-between">
        <span>站内信</span>
        <el-button type="primary" link @click="onReadAll">全部已读</el-button>
      </div>
    </template>
    <el-table :data="records" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="title" label="标题" show-overflow-tooltip />
      <el-table-column prop="content" label="内容" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.isRead === 1 ? 'info' : 'danger'">{{ row.isRead === 1 ? '已读' : '未读' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="时间" width="180" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button v-if="row.isRead === 0" type="primary" link @click="onRead(row.id)">标记已读</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pageMessages, readAllMessages, readMessage } from '@/api/message'

const loading = ref(false)
const records = ref<any[]>([])

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageMessages({ page: 1, pageSize: 20 })
    records.value = res.data.records
  } finally {
    loading.value = false
  }
}
const onRead = async (id: number) => {
  await readMessage(id)
  ElMessage.success('已标记已读')
  load()
}
const onReadAll = async () => {
  await readAllMessages()
  ElMessage.success('已全部已读')
  load()
}
onMounted(load)
</script>
