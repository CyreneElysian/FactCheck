<template>
  <el-card>
    <template #header>
      <div style="display: flex; align-items: center; justify-content: space-between">
        <span>热门消息 Top10</span>
        <span style="color: #909399; font-size: 12px">点击任意一行查看详情</span>
      </div>
    </template>
    <!-- P3-34：整行可点击进入详情（走 /hot/{id} 公开接口，服务端只放行榜内记录） -->
    <el-table
      v-loading="loading"
      :data="list"
      row-key="recordId"
      highlight-current-row
      style="cursor: pointer"
      @row-click="onRowClick"
    >
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
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button type="primary" link @click.stop="goDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getHotTop10 } from '@/api/hot'

const router = useRouter()
const loading = ref(false)
const list = ref<any[]>([])

const goDetail = (row: any) => {
  if (row && row.recordId) {
    router.push(`/hot/${row.recordId}`)
  }
}
const onRowClick = (row: any) => goDetail(row)

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
