<template>
  <el-card>
    <template #header>热门消息 Top10</template>
    <el-table :data="list" v-loading="loading">
      <el-table-column prop="recordId" label="ID" width="80" />
      <el-table-column prop="content" label="消息内容" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column label="结论" width="100">
        <template #default="{ row }">
          <el-tag :type="row.verdict === 1 ? 'success' : row.verdict === 2 ? 'danger' : 'info'">
            {{ row.verdict === 1 ? '真' : row.verdict === 2 ? '假' : '无法判定' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="hotScore" label="热度" width="100" />
      <el-table-column prop="hitCount" label="检测次数" width="100" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHotTop10 } from '@/api/hot'

const loading = ref(false)
const list = ref<any[]>([])

onMounted(async () => {
  loading.value = true
  try {
    const res: any = await getHotTop10()
    list.value = res.data
  } finally {
    loading.value = false
  }
})
</script>
