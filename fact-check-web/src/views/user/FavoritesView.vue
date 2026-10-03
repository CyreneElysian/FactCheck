<template>
  <el-card>
    <template #header>我的收藏</template>
    <el-table v-loading="loading" :data="records">
      <el-table-column prop="recordId" label="记录ID" width="100" />
      <el-table-column prop="content" label="检测内容" show-overflow-tooltip />
      <el-table-column label="结论" width="100">
        <template #default="{ row }">
          <el-tag :type="row.verdict === 1 ? 'success' : row.verdict === 2 ? 'danger' : 'info'">
            {{ row.verdict === 1 ? '真' : row.verdict === 2 ? '假' : '无法判定' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column prop="createTime" label="收藏时间" width="180" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button type="primary" link @click="goDetail(row.recordId)">详情</el-button>
          <el-button type="danger" link @click="onRemove(row.recordId)">取消收藏</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- P3-36：原先固定只取前 20 条且无分页 -->
    <el-pagination
      style="margin-top: 12px"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-sizes="[10, 20, 50]"
      :page-size="query.pageSize"
      :current-page="query.page"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { pageMyFavorites, removeFavorite } from '@/api/favorite'

const router = useRouter()
const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10 })

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageMyFavorites(query)
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
const onSizeChange = (size: number) => {
  query.pageSize = size
  query.page = 1
  load()
}
const goDetail = (recordId: number) => router.push(`/history/${recordId}`)
const onRemove = async (recordId: number) => {
  await removeFavorite(recordId)
  ElMessage.success('已取消收藏')
  load()
}
onMounted(load)
</script>
