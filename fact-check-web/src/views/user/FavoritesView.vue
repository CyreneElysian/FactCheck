<template>
  <el-card>
    <template #header>我的收藏</template>
    <el-table :data="records" v-loading="loading">
      <el-table-column prop="recordId" label="记录ID" width="100" />
      <el-table-column prop="content" label="检测内容" show-overflow-tooltip />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button type="danger" link @click="onRemove(row.recordId)">取消收藏</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pageMyFavorites, removeFavorite } from '@/api/favorite'

const loading = ref(false)
const records = ref<any[]>([])

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageMyFavorites({ page: 1, pageSize: 20 })
    records.value = res.data.records
  } finally {
    loading.value = false
  }
}
const onRemove = async (recordId: number) => {
  await removeFavorite(recordId)
  ElMessage.success('已取消收藏')
  load()
}
onMounted(load)
</script>
