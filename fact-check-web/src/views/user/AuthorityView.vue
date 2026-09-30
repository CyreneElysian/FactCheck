<template>
  <el-card>
    <template #header>权威信息</template>
    <el-form inline>
      <el-form-item label="分类">
        <el-select v-model="query.category" clearable placeholder="全部" style="width: 160px">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="query.keyword" placeholder="搜索标题" clearable style="width: 200px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="records" v-loading="loading">
      <el-table-column prop="title" label="标题" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="120" />
      <el-table-column prop="source" label="来源" width="180" />
      <el-table-column prop="publishTime" label="发布时间" width="180" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button type="primary" link @click="$router.push(`/authority/${row.id}`)">详情</el-button>
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
import { pageAuthority } from '@/api/authority'

const categories = ['健康', '政策', '社会', '财经', '科技']
const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ category: '', keyword: '', page: 1, pageSize: 10 })

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageAuthority(query)
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
