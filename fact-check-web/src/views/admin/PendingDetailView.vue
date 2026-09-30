<template>
  <el-card v-loading="loading">
    <template #header>诊断工作台 #{{ id }}</template>
    <p><b>消息内容：</b>{{ detail.content }}</p>
    <p v-if="detail.category"><b>分类：</b>{{ detail.category }}</p>
    <el-table v-if="detail.evidenceList && detail.evidenceList.length" :data="detail.evidenceList" style="margin-top: 12px">
      <el-table-column prop="title" label="证据标题" />
      <el-table-column prop="source" label="来源" width="140" />
      <el-table-column prop="sourceCredibility" label="可信度" width="90" />
      <el-table-column prop="relevanceScore" label="相关度" width="90" />
    </el-table>

    <el-divider />
    <el-form label-width="80px" style="max-width: 640px">
      <el-form-item label="结论">
        <el-radio-group v-model="form.verdict">
          <el-radio :label="1">真</el-radio>
          <el-radio :label="2">假</el-radio>
          <el-radio :label="3">无法判定</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="推理依据">
        <el-input v-model="form.reasoningChain" type="textarea" :rows="4" placeholder="结论依据（必填）" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSubmit">提交诊断</el-button>
      </el-form-item>
    </el-form>

    <el-divider />
    <el-form inline>
      <el-form-item label="转派给">
        <el-input v-model="assigneeId" placeholder="管理员ID" style="width: 160px" />
      </el-form-item>
      <el-form-item>
        <el-button type="warning" @click="onAssign">转派</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { assignPending, getPendingDetail, submitConclusion } from '@/api/admin'

const route = useRoute()
const router = useRouter()
const id = route.params.id as string
const loading = ref(false)
const detail = ref<any>({})
const assigneeId = ref('')
const form = reactive({ verdict: 1, reasoningChain: '' })

onMounted(async () => {
  loading.value = true
  try {
    const res: any = await getPendingDetail(id)
    detail.value = res.data
  } finally {
    loading.value = false
  }
})

const onSubmit = async () => {
  if (!form.reasoningChain) {
    ElMessage.warning('请填写推理依据')
    return
  }
  await submitConclusion(id, form)
  ElMessage.success('诊断完成')
  router.push('/admin/pending')
}

const onAssign = async () => {
  if (!assigneeId.value) {
    ElMessage.warning('请输入转派管理员ID')
    return
  }
  await assignPending(id, { assigneeId: Number(assigneeId.value) })
  ElMessage.success('已转派')
}
</script>
