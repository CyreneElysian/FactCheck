<template>
  <el-card>
    <template #header>历史记录</template>
    <el-form inline>
      <el-form-item label="分类">
        <el-select v-model="query.category" clearable placeholder="全部" style="width: 140px">
          <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px">
          <el-option label="检测中" :value="0" />
          <el-option label="待人工" :value="1" />
          <el-option label="已完成" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="records">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="content" label="内容" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column label="结论" width="100">
        <template #default="{ row }">
          <el-tag
            v-if="row.status === 2"
            :type="row.verdict === 1 ? 'success' : row.verdict === 2 ? 'danger' : 'info'"
          >
            {{ verdictText(row.verdict) }}
          </el-tag>
          <el-tag v-else-if="row.status === 1" type="warning">待人工</el-tag>
          <el-tag v-else type="info">检测中</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="提交时间" width="180" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button type="primary" link @click="$router.push(`/history/${row.id}`)">详情</el-button>
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
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { pageDetectRecords } from '@/api/detect'
import { CATEGORIES } from '@/constants/categories'

const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ category: '', status: undefined as number | undefined, page: 1, pageSize: 10 })

const verdictText = (v: number) => (v === 1 ? '真' : v === 2 ? '假' : '无法判定')

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageDetectRecords(query)
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
