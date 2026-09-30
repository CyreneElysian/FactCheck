<template>
  <el-card>
    <template #header>结论管理</template>
    <el-table :data="records" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="content" label="内容" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column label="结论" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.status === 2" :type="row.verdict === 1 ? 'success' : row.verdict === 2 ? 'danger' : 'info'">
            {{ row.verdict === 1 ? '真' : row.verdict === 2 ? '假' : '无法判定' }}
          </el-tag>
          <el-tag v-else-if="row.status === 1" type="warning">待人工</el-tag>
          <el-tag v-else type="info">检测中</el-tag>
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
    <el-pagination
      style="margin-top: 12px"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.pageSize"
      :current-page="query.page"
      @current-change="onPageChange"
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

const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10 })
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
