<template>
  <el-card>
    <template #header>结论管理</template>
    <!-- P3-36：补筛选（文档 §4.8 要求按状态/结论/分类/关键词） -->
    <el-form inline>
      <el-form-item label="分类">
        <el-select v-model="query.category" clearable placeholder="全部" style="width: 140px" @change="onSearch">
          <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable placeholder="全部" style="width: 150px" @change="onSearch">
          <el-option label="检测中" :value="0" />
          <el-option label="待人工诊断" :value="1" />
          <el-option label="已完成" :value="2" />
          <el-option label="检测失败" :value="3" />
        </el-select>
      </el-form-item>
      <el-form-item label="结论">
        <el-select v-model="query.verdict" clearable placeholder="全部" style="width: 130px" @change="onSearch">
          <el-option label="真" :value="1" />
          <el-option label="假" :value="2" />
          <el-option label="无法判定" :value="3" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="query.keyword" clearable placeholder="搜索消息内容" style="width: 200px" @keyup.enter="onSearch" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="onReset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="records" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="content" label="内容" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column label="结论" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.status === 2" :type="row.verdict === 1 ? 'success' : row.verdict === 2 ? 'danger' : 'info'">
            {{ row.verdict === 1 ? '真' : row.verdict === 2 ? '假' : '无法判定' }}
          </el-tag>
          <el-tag v-else-if="row.status === 1" type="warning">待人工</el-tag>
          <el-tag v-else-if="row.status === 3" type="danger">检测失败</el-tag>
          <el-tag v-else type="info">检测中</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="判定来源" width="100">
        <template #default="{ row }">
          {{ row.verdictSource === 2 ? '人工' : row.status === 2 ? 'AI' : '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button type="primary" link @click="openEdit(row)">修改结论</el-button>
          <el-button type="info" link @click="openHistory(row)">历史</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- P3-36：补每页条数切换 -->
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

    <el-dialog v-model="historyVisible" title="结论修订历史" width="720px">
      <el-table :data="historyRecords" v-loading="historyLoading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="旧结论" width="90">
          <template #default="{ row }">{{ verdictText(row.oldVerdict) }}</template>
        </el-table-column>
        <el-table-column label="新结论" width="90">
          <template #default="{ row }">{{ verdictText(row.newVerdict) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="修改原因" show-overflow-tooltip />
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ row.source === 1 ? '管理员修改' : '申诉采纳' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="180" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="editVisible" title="修改结论" width="520px">
      <el-form label-width="80px">
        <el-form-item label="新结论">
          <el-radio-group v-model="editForm.verdict">
            <el-radio :label="1">真</el-radio>
            <el-radio :label="2">假</el-radio>
            <el-radio :label="3">无法判定</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="新推理链">
          <el-input v-model="editForm.reasoningChain" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="修改原因">
          <el-input v-model="editForm.reason" type="textarea" :rows="2" placeholder="必填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="onUpdate">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getRecordRevisions, pageAdminRecords, updateConclusion } from '@/api/admin'
import { CATEGORIES } from '@/constants/categories'

const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
// P3-36：筛选条件与分页一起作为查询入参
const query = reactive({
  category: '',
  status: undefined as number | undefined,
  verdict: undefined as number | undefined,
  keyword: '',
  page: 1,
  pageSize: 10
})
const editVisible = ref(false)
const editId = ref<number | null>(null)
const editForm = reactive({ verdict: 1, reasoningChain: '', reason: '' })
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyRecords = ref<any[]>([])

const verdictText = (v: number) => (v === 1 ? '真' : v === 2 ? '假' : '无法判定')

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageAdminRecords(query)
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
/** 筛选条件变化时回到第 1 页（否则可能停在一个空页上） */
const onSearch = () => {
  query.page = 1
  load()
}
const onReset = () => {
  query.category = ''
  query.status = undefined
  query.verdict = undefined
  query.keyword = ''
  query.page = 1
  load()
}
const onSizeChange = (size: number) => {
  query.pageSize = size
  query.page = 1
  load()
}
const openEdit = (row: any) => {
  editId.value = row.id
  editForm.verdict = row.verdict || 1
  editForm.reasoningChain = row.reasoningChain || ''
  editForm.reason = ''
  editVisible.value = true
}
const openHistory = async (row: any) => {
  historyVisible.value = true
  historyLoading.value = true
  try {
    const res: any = await getRecordRevisions(row.id)
    historyRecords.value = res.data
  } finally {
    historyLoading.value = false
  }
}
const onUpdate = async () => {
  if (!editForm.reason) {
    ElMessage.warning('请填写修改原因')
    return
  }
  await updateConclusion(editId.value!, editForm)
  ElMessage.success('已修改')
  editVisible.value = false
  load()
}
onMounted(load)
</script>
