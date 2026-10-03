<template>
  <el-card>
    <template #header>操作日志</template>
    <el-table v-loading="loading" :data="records">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="adminId" label="操作人ID" width="100" />
      <el-table-column prop="operation" label="操作" width="160" />
      <el-table-column prop="method" label="方法" show-overflow-tooltip />
      <el-table-column prop="params" label="参数" show-overflow-tooltip />
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column prop="createTime" label="时间" width="180" />
    </el-table>
    <el-pagination
      style="margin-top: 12px"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.pageSize"
      :current-page="query.page"
      @current-change="onPageChange"
    />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { pageAdminLogs } from '@/api/admin'

const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10 })

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageAdminLogs(query)
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
onMounted(load)
</script>
