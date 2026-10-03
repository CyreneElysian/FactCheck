<template>
  <div>
    <el-card>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="文字检测" name="text" />
        <el-tab-pane label="图片检测" name="image" />
      </el-tabs>

      <el-form label-width="80px">
        <el-form-item label="消息分类">
          <el-select v-model="category" style="width: 240px">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="activeTab === 'text'" label="消息内容">
          <el-input
            v-model="content"
            type="textarea"
            :rows="4"
            maxlength="5000"
            show-word-limit
            placeholder="请输入要诊断的消息"
          />
        </el-form-item>

        <el-form-item v-else label="上传图片">
          <el-upload :show-file-list="false" :http-request="onUploadImage">
            <el-button :loading="ocrLoading">上传图片并识别</el-button>
          </el-upload>
          <div v-if="imageUrl" style="margin-top: 8px">
            <el-image :src="imageUrl" style="max-width: 300px" fit="contain" />
          </div>
        </el-form-item>

        <el-form-item v-if="activeTab === 'image' && imageUrl" label="识别文字">
          <el-input v-model="content" type="textarea" :rows="4" placeholder="OCR 识别结果，可编辑后提交" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="onSubmit">提交检测</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="result" style="margin-top: 16px">
      <template #header>
        <div style="display: flex; align-items: center; gap: 12px">
          <span>检测结果</span>
          <el-tag v-if="result.status === 0" type="info">检测中</el-tag>
          <el-tag v-else-if="result.status === 1" type="warning">待人工诊断</el-tag>
          <el-tag v-else-if="result.status === 2" :type="verdictTagType">{{ verdictText }}</el-tag>
          <el-tag v-else type="danger">检测失败</el-tag>
        </div>
      </template>
      <div v-if="result.status === 0">
        <el-alert title="正在检测，请稍候..." type="info" :closable="false" />
      </div>
      <div v-else>
        <!-- P3-40：待人工诊断要给明确文案，而不是只显示一个标签 -->
        <el-alert
          v-if="result.status === 1"
          type="warning"
          :closable="false"
          style="margin-bottom: 12px"
          title="该消息已进入人工诊断队列，请稍后在「历史记录」中查看结论"
        />
        <el-alert
          v-if="result.status === 3"
          type="error"
          :closable="false"
          style="margin-bottom: 12px"
          title="检测失败（服务暂时不可用），可重新提交相同内容"
        />
        <el-alert v-if="result.authorityInfo" type="success" :closable="false" style="margin-bottom: 12px">
          <template #title>
            已命中权威信息：{{ result.authorityInfo.title }}
            <el-button type="primary" link @click="$router.push(`/authority/${result.authorityInfo.id}`)">
              查看权威详情
            </el-button>
          </template>
        </el-alert>
        <p><b>消息内容：</b>{{ result.content }}</p>
        <p v-if="result.category"><b>分类：</b>{{ result.category }}</p>
        <!-- P3-40：置信度原先完全不展示 -->
        <p v-if="confidenceText"><b>置信度：</b>{{ confidenceText }}</p>
        <div v-if="result.reasoningChain">
          <b>推理链：</b>
          <pre style="white-space: pre-wrap">{{ result.reasoningChain }}</pre>
        </div>
        <div v-if="result.aiSummary"><b>AI 说明：</b>{{ result.aiSummary }}</div>
        <el-table
          v-if="result.evidenceList && result.evidenceList.length"
          :data="result.evidenceList"
          style="margin-top: 12px"
        >
          <el-table-column label="证据标题">
            <template #default="{ row }">
              <el-link v-if="row.url" type="primary" :href="row.url" target="_blank" rel="noopener">
                {{ row.title }}
              </el-link>
              <span v-else>{{ row.title }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="120" />
          <el-table-column label="可信度" width="90">
            <template #default="{ row }">{{ percent(row.sourceCredibility) }}</template>
          </el-table-column>
          <el-table-column label="相关度" width="90">
            <template #default="{ row }">{{ percent(row.relevanceScore) }}</template>
          </el-table-column>
        </el-table>
        <div style="margin-top: 12px">
          <el-button type="primary" link @click="$router.push(`/history/${result.id}`)"
            >查看详情/申诉/收藏</el-button
          >
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getDetectRecord, recognizeImage, submitDetect } from '@/api/detect'
import { CATEGORIES } from '@/constants/categories'
import { connectDetectSocket } from '@/utils/ws'

const activeTab = ref('text')
const category = ref('其他')
const content = ref('')
const imageUrl = ref('')
const ocrLoading = ref(false)
const submitting = ref(false)
const result = ref<any>(null)
let timer: number | undefined
let closeWs: (() => void) | null = null

const verdictText = computed(() => {
  const v = result.value?.verdict
  return v === 1 ? '真' : v === 2 ? '假' : v === 3 ? '无法判定' : '未知'
})
const verdictTagType = computed(() => {
  const v = result.value?.verdict
  return v === 1 ? 'success' : v === 2 ? 'danger' : 'info'
})
/** P3-40：置信度（后端为 0-1 的小数）显示为百分比 */
const confidenceText = computed(() => {
  const c = result.value?.confidence
  return c === null || c === undefined || isNaN(Number(c)) ? '' : `${Math.round(Number(c) * 100)}%`
})
/** 证据可信度/相关度同样按百分比展示 */
const percent = (value: any) =>
  value === null || value === undefined || isNaN(Number(value)) ? '-' : `${Math.round(Number(value) * 100)}%`

const onUploadImage = async (options: any) => {
  ocrLoading.value = true
  try {
    const res: any = await recognizeImage(options.file)
    imageUrl.value = res.data.imageUrl
    content.value = res.data.text
    ElMessage.success('图片识别完成，可编辑后提交')
  } finally {
    ocrLoading.value = false
  }
}

const onSubmit = async () => {
  if (!content.value.trim()) {
    ElMessage.warning('请输入消息内容')
    return
  }
  submitting.value = true
  result.value = null
  try {
    const res: any = await submitDetect({
      content: content.value,
      sourceType: activeTab.value === 'image' ? 2 : 1,
      imageUrl: imageUrl.value || undefined,
      category: category.value
    })
    const recordId = res.data
    ElMessage.success('已提交，正在检测')
    startDetection(recordId)
  } finally {
    submitting.value = false
  }
}

const stopPolling = () => {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

const startPolling = (recordId: number) => {
  stopPolling()
  timer = window.setInterval(async () => {
    try {
      const res: any = await getDetectRecord(recordId)
      result.value = res.data
      if (res.data.status !== 0) {
        stopPolling()
      }
    } catch (e) {
      stopPolling()
    }
  }, 2000)
}

const startDetection = (recordId: number) => {
  startPolling(recordId)
  if (closeWs) closeWs()
  closeWs = connectDetectSocket(recordId, async msg => {
    if (msg.recordId === recordId && msg.status !== 0) {
      stopPolling()
      if (closeWs) {
        closeWs()
        closeWs = null
      }
      // 等事务提交后再查一次，避免读到旧状态
      await new Promise(resolve => setTimeout(resolve, 500))
      try {
        const res: any = await getDetectRecord(recordId)
        result.value = res.data
      } catch (e) {
        // 查询失败则忽略，页面已有轮询结果兜底
      }
    }
  })
}

onUnmounted(() => {
  stopPolling()
  if (closeWs) closeWs()
})
</script>
