<template>
  <el-card>
    <template #header>待诊断热门消息 Top10</template>
    <el-table v-loading="loading" :data="list">
      <el-table-column prop="recordId" label="ID" width="80" />
      <el-table-column prop="content" label="消息内容" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column prop="requestCount" label="请求次数" width="100" />
      <el-table-column label="是否超时" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.timeout" type="danger">已超时</el-tag>
          <el-tag v-else type="success">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button type="primary" link @click="$router.push(`/admin/pending/${row.recordId}`)"
            >诊断</el-button
          >
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getPendingTop10 } from '@/api/admin'

const loading = ref(false)
const list = ref<any[]>([])

onMounted(async () => {
  loading.value = true
  try {
    const res: any = await getPendingTop10()
    list.value = res.data
  } finally {
    loading.value = false
  }
})
</script>
