<template>
  <el-card>
    <template #header>我的申诉</template>
    <el-table :data="records" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="recordContent" label="检测内容" show-overflow-tooltip />
      <el-table-column prop="reasonType" label="类型" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'warning'">
            {{ row.status === 0 ? '待处理' : row.status === 1 ? '已采纳' : '已驳回' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="handleResult" label="处理结果" show-overflow-tooltip />
      <el-table-column prop="createTime" label="提交时间" width="180" />
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
import { pageMyAppeals } from '@/api/appeal'

const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ status: undefined as number | undefined, page: 1, pageSize: 10 })

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageMyAppeals(query)
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
