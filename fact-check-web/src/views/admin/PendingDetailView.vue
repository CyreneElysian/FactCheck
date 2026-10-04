<template>
  <el-card v-loading="loading">
    <template #header>
      <div style="display: flex; align-items: center; gap: 12px">
        <span>诊断工作台 #{{ id }}</span>
        <el-tag v-if="detail.status === 1" type="warning">待人工诊断</el-tag>
        <el-tag v-else-if="detail.status === 2" type="success">已完成</el-tag>
        <el-tag v-else type="info">状态 {{ detail.status }}</el-tag>
        <el-tag v-if="isTimeout" type="danger">已超时</el-tag>
      </div>
    </template>

    <!-- 消息本体：文字 + 图片 + OCR 文本（P3-35：原先图片与置信度都没展示） -->
    <el-descriptions :column="2" border style="margin-bottom: 12px">
      <el-descriptions-item label="分类">{{ detail.category || '未分类' }}</el-descriptions-item>
      <el-descriptions-item label="来源类型">
        {{ detail.sourceType === 2 ? '图片检测（含 OCR）' : '文字检测' }}
      </el-descriptions-item>
      <el-descriptions-item label="AI 置信度">
        <!-- 置信度是"AI 有多确信"，与结论同等重要，人工诊断时需要据此判断 -->
        <span v-if="confidenceText">{{ confidenceText }}</span>
        <span v-else style="color: #909399">无（AI 未能判定）</span>
      </el-descriptions-item>
      <el-descriptions-item label="诊断截止时间">
        <span v-if="detail.deadline">{{ detail.deadline }}</span>
        <span v-else style="color: #909399">未设置</span>
      </el-descriptions-item>
      <el-descriptions-item label="申请次数">{{ detail.hitCount ?? '-' }}</el-descriptions-item>
      <el-descriptions-item label="当前指派人">
        <span v-if="detail.assigneeId">{{ assigneeName(detail.assigneeId) }}</span>
        <span v-else style="color: #909399">未指派</span>
      </el-descriptions-item>
      <el-descriptions-item label="提交时间">{{ detail.createTime }}</el-descriptions-item>
      <el-descriptions-item label="版本">v{{ detail.version ?? 1 }}</el-descriptions-item>
    </el-descriptions>

    <p><b>消息内容：</b></p>
    <pre class="content-block">{{ detail.content }}</pre>

    <div v-if="detail.imageUrl" style="margin-top: 12px">
      <b>用户上传图片：</b>
      <div>
        <el-image
          :src="detail.imageUrl"
          style="max-width: 320px"
          fit="contain"
          :preview-src-list="[detail.imageUrl]"
        />
      </div>
      <div style="color: #909399; font-size: 12px">
        上方"消息内容"为 OCR 识别文本，可由管理员核对后作为判定依据
      </div>
    </div>

    <el-alert v-if="detail.aiSummary" type="info" :closable="false" style="margin-top: 12px">
      <template #title>AI 说明：{{ detail.aiSummary }}</template>
    </el-alert>

    <!-- 证据管理（P3-35：原先只能看，不能补充/删除） -->
    <el-divider content-position="left">
      证据（{{ evidenceList.length }} 条，其中管理员补充 {{ manualCount }} 条）
    </el-divider>

    <el-table v-loading="evidenceLoading" :data="evidenceList" size="small">
      <el-table-column label="标题" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link v-if="row.url" type="primary" :href="row.url" target="_blank" rel="noopener">{{
            row.title
          }}</el-link>
          <span v-else>{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="130" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="row.sourceType === 2 ? 'warning' : 'info'" size="small">
            {{ row.sourceType === 2 ? '管理员补充' : 'AI 检索' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可信度" width="90">
        <template #default="{ row }">{{ percent(row.sourceCredibility) }}</template>
      </el-table-column>
      <el-table-column label="相关度" width="90">
        <template #default="{ row }">{{ percent(row.relevanceScore) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button type="danger" link @click="onRemoveEvidence(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 12px">
      <el-button type="primary" plain @click="evidenceFormVisible = !evidenceFormVisible">
        {{ evidenceFormVisible ? '收起' : '补充证据' }}
      </el-button>
    </div>

    <el-form v-if="evidenceFormVisible" label-width="80px" style="max-width: 720px; margin-top: 12px">
      <el-form-item label="标题" required>
        <el-input
          v-model="evidenceForm.title"
          maxlength="200"
          show-word-limit
          placeholder="例如：湖北省卫健委 2026-10-03 通报"
        />
      </el-form-item>
      <el-form-item label="链接">
        <el-input v-model="evidenceForm.url" placeholder="https://…（选填，必须是 http/https）" />
      </el-form-item>
      <el-form-item label="摘要">
        <el-input v-model="evidenceForm.snippet" type="textarea" :rows="2" maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="evidenceSubmitting" @click="onAddEvidence">添加证据</el-button>
      </el-form-item>
    </el-form>

    <el-divider content-position="left">人工诊断结论</el-divider>
    <el-form label-width="90px" style="max-width: 720px">
      <el-form-item label="结论">
        <el-radio-group v-model="form.verdict">
          <el-radio :label="1">真</el-radio>
          <el-radio :label="2">假</el-radio>
          <el-radio :label="3">无法判定</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="推理依据" required>
        <el-input
          v-model="form.reasoningChain"
          type="textarea"
          :rows="5"
          maxlength="2000"
          show-word-limit
          placeholder="结论依据（必填）：说明为什么判定为该结论，并引用上面的证据"
        />
      </el-form-item>
      <!-- P3-35：发布为权威信息（AI 已判定为假/需辟谣的内容可直接沉淀为权威库） -->
      <el-form-item label="同步操作">
        <el-checkbox v-model="publishAsAuthority">同时发布为权威信息（标题与内容取自本次判定）</el-checkbox>
      </el-form-item>
      <el-form-item v-if="publishAsAuthority" label="权威分类">
        <el-select v-model="authorityCategory" style="width: 200px">
          <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="onSubmit">提交诊断</el-button>
      </el-form-item>
    </el-form>

    <el-divider />
    <!-- P3-35：转派改为下拉选择真实管理员（原先手填 ID，填错即无效指派） -->
    <el-form inline>
      <el-form-item label="转派给">
        <el-select v-model="assigneeId" placeholder="选择管理员" style="width: 220px" filterable>
          <el-option
            v-for="a in admins"
            :key="a.id"
            :label="a.nickname ? `${a.nickname}（${a.username}）` : a.username"
            :value="a.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="warning" :disabled="!assigneeId" @click="onAssign">转派</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addEvidence,
  assignPending,
  getPendingDetail,
  listAdmins,
  removeEvidence,
  saveAuthority,
  submitConclusion
} from '@/api/admin'
import { CATEGORIES } from '@/constants/categories'
import { percent as formatPercent, percentOrEmpty } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const id = route.params.id as string
const loading = ref(false)
const detail = ref<any>({})
const admins = ref<any[]>([])
const assigneeId = ref<number | null>(null)
const submitting = ref(false)
const form = reactive({ verdict: 1, reasoningChain: '' })
const publishAsAuthority = ref(false)
const authorityCategory = ref('其他')

// 证据管理
const evidenceList = ref<any[]>([])
const evidenceLoading = ref(false)
const evidenceFormVisible = ref(false)
const evidenceSubmitting = ref(false)
const evidenceForm = reactive({ title: '', url: '', snippet: '' })

const confidenceText = computed(() => percentOrEmpty(detail.value.confidence))
const manualCount = computed(() => evidenceList.value.filter(e => e.sourceType === 2).length)
const isTimeout = computed(() => {
  const deadline = detail.value.deadline
  return !!deadline && new Date(String(deadline).replace(/-/g, '/')) < new Date()
})
/**
 * 证据可信度/相关度：管理员补充的证据这两项**刻意留空**（不臆造分值），
 * 因此空值必须显示 '-' —— 旧实现遇到空串会显示 0%，等于告诉管理员"这是 0 分"。
 */
const percent = (value: any) => formatPercent(value)

const assigneeName = (adminId: number) => {
  const hit = admins.value.find(a => a.id === adminId)
  return hit ? hit.nickname || hit.username : `#${adminId}`
}

const load = async () => {
  loading.value = true
  try {
    const res: any = await getPendingDetail(id)
    detail.value = res.data || {}
    evidenceList.value = detail.value.evidenceList || []
    form.verdict = detail.value.verdict || 1
    form.reasoningChain = detail.value.reasoningChain || ''
  } finally {
    loading.value = false
  }
}

const loadAdmins = async () => {
  try {
    const res: any = await listAdmins()
    admins.value = res.data || []
  } catch (e) {
    // 拿不到列表时仍可用手填 ID 的兜底（下方提示）
  }
}

const refreshEvidence = async () => {
  evidenceLoading.value = true
  try {
    const res: any = await getPendingDetail(id)
    detail.value = res.data || {}
    evidenceList.value = detail.value.evidenceList || []
  } finally {
    evidenceLoading.value = false
  }
}

const onAddEvidence = async () => {
  if (!evidenceForm.title.trim()) {
    ElMessage.warning('请填写证据标题')
    return
  }
  evidenceSubmitting.value = true
  try {
    await addEvidence(id, { ...evidenceForm })
    ElMessage.success('证据已添加')
    evidenceForm.title = ''
    evidenceForm.url = ''
    evidenceForm.snippet = ''
    evidenceFormVisible.value = false
    await refreshEvidence()
  } finally {
    evidenceSubmitting.value = false
  }
}

const onRemoveEvidence = async (row: any) => {
  await ElMessageBox.confirm(`确定删除证据「${row.title}」？`, '提示', { type: 'warning' })
  await removeEvidence(id, row.id)
  ElMessage.success('证据已删除')
  await refreshEvidence()
}

const onSubmit = async () => {
  if (!form.reasoningChain.trim()) {
    ElMessage.warning('请填写推理依据')
    return
  }
  submitting.value = true
  try {
    await submitConclusion(id, { ...form })
    if (publishAsAuthority.value) {
      await saveAuthority({
        title: buildAuthorityTitle(),
        content: form.reasoningChain,
        category: authorityCategory.value,
        source: '人工诊断沉淀'
      })
      ElMessage.success('诊断完成，并已发布为权威信息')
    } else {
      ElMessage.success('诊断完成')
    }
    router.push('/admin/pending')
  } finally {
    submitting.value = false
  }
}

/** 权威信息标题：截取消息前 40 字并标注结论 */
const buildAuthorityTitle = () => {
  const content = String(detail.value.content || '')
    .replace(/\s+/g, ' ')
    .trim()
  const short = content.length > 40 ? content.slice(0, 40) + '…' : content
  const verdict = form.verdict === 1 ? '真' : form.verdict === 2 ? '假' : '无法判定'
  return `【${verdict}】${short}`
}

const onAssign = async () => {
  if (!assigneeId.value) {
    ElMessage.warning('请选择转派的管理员')
    return
  }
  await assignPending(id, { assigneeId: assigneeId.value })
  ElMessage.success('已转派')
  await load()
}

onMounted(async () => {
  await Promise.all([load(), loadAdmins()])
})
</script>

<style scoped>
.content-block {
  white-space: pre-wrap;
  word-break: break-all;
  background: #f5f7fa;
  border-radius: 4px;
  padding: 12px;
  margin: 8px 0 0;
  font-family: inherit;
}
</style>
