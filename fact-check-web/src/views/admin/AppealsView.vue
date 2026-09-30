<template>
  <el-card>
    <template #header>申诉处理</template>
    <el-table :data="records" v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="recordId" label="记录ID" width="100" />
      <el-table-column prop="reasonType" label="类型" width="100" />
      <el-table-column prop="content" label="申诉说明" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'warning'">
            {{ row.status === 0 ? '待处理' : row.status === 1 ? '已采纳' : '已驳回' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" type="primary" link @click="openHandle(row)">处理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="handleVisible" title="处理申诉" width="520px">
      <el-form label-width="80px">
        <el-form-item label="处理方式">
          <el-radio-group v-model="handleForm.accept">
            <el-radio :label="true">采纳</el-radio>
            <el-radio :label="false">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="handleForm.handleResult" type="textarea" :rows="3" placeholder="驳回时填写原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" @click="onHandle">提交</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { handleAppeal, pageAdminAppeals } from '@/api/admin'

const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10 })
const handleVisible = ref(false)
const handleId = ref<number | null>(null)
const handleForm = reactive({ accept: true, handleResult: '' })

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageAdminAppeals(query)
    records.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}
const openHandle = (row: any) => {
  handleId.value = row.id
  handleForm.accept = true
  handleForm.handleResult = ''
  handleVisible.value = true
}
const onHandle = async () => {
  await handleAppeal(handleId.value!, handleForm)
  ElMessage.success('已处理')
  handleVisible.value = false
  load()
}
onMounted(load)
</script>
