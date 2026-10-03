<template>
  <el-card v-loading="loading">
    <template #header>
      <div style="display: flex; align-items: center; gap: 12px">
        <span>热门消息详情 #{{ id }}</span>
        <el-tag v-if="record.verdict" :type="verdictTagType">{{ verdictText }}</el-tag>
        <el-tag v-else type="info">{{ statusText }}</el-tag>
        <el-tag type="warning" effect="plain">公开视图</el-tag>
      </div>
    </template>

    <el-alert type="info" :closable="false" style="margin-bottom: 12px">
      <template #title>
        该消息因热度进入 Top10 而公开展示；只有当前仍在榜内的记录可被查看。
        收藏、评分与申诉请到「历史记录」中打开自己的记录。
      </template>
    </el-alert>

    <el-alert v-if="record.authorityInfo" type="success" :closable="false" style="margin-bottom: 12px">
      <template #title>
        已命中权威信息：{{ record.authorityInfo.title }}
        <el-button type="primary" link @click="$router.push(`/authority/${record.authorityInfo.id}`)">
          查看权威详情
        </el-button>
      </template>
    </el-alert>

    <p><b>内容：</b>{{ record.content }}</p>
    <p v-if="record.category"><b>分类：</b>{{ record.category }}</p>
    <p v-if="record.confidence !== null && record.confidence !== undefined">
      <b>置信度：</b>{{ confidenceText }}
    </p>
    <p v-if="record.imageUrl">
      <el-image :src="record.imageUrl" style="max-width: 300px" fit="contain" />
    </p>

    <div v-if="record.reasoningChain" style="margin-top: 12px">
      <b>推理依据：</b>
      <pre style="white-space: pre-wrap">{{ record.reasoningChain }}</pre>
    </div>

    <div v-if="record.aiSummary" style="margin-top: 12px"><b>AI 说明：</b>{{ record.aiSummary }}</div>

    <el-table
      v-if="record.evidenceList && record.evidenceList.length"
      :data="record.evidenceList"
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
      <el-table-column prop="source" label="来源" width="140" />
      <el-table-column label="可信度" width="100">
        <template #default="{ row }">{{ percent(row.sourceCredibility) }}</template>
      </el-table-column>
      <el-table-column label="相关度" width="100">
        <template #default="{ row }">{{ percent(row.relevanceScore) }}</template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 16px">
      <el-button @click="$router.push('/hot')">返回榜单</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getHotRecord } from '@/api/hot'

const route = useRoute()
const id = route.params.id as string
const loading = ref(false)
const record = ref<any>({})

const verdictText = computed(() => {
  const v = record.value.verdict
  return v === 1 ? '真' : v === 2 ? '假' : v === 3 ? '无法判定' : '待诊断'
})
const verdictTagType = computed(() => {
  const v = record.value.verdict
  return v === 1 ? 'success' : v === 2 ? 'danger' : 'info'
})
const statusText = computed(() => {
  const s = record.value.status
  return s === 0 ? '检测中' : s === 1 ? '待人工诊断' : s === 3 ? '检测失败' : '已完成'
})
const confidenceText = computed(() =>
  record.value.confidence === null || record.value.confidence === undefined
    ? '-'
    : `${Math.round(Number(record.value.confidence) * 100)}%`
)

const percent = (value: any) =>
  value === null || value === undefined || isNaN(Number(value)) ? '-' : `${Math.round(Number(value) * 100)}%`

onMounted(async () => {
  loading.value = true
  try {
    const res: any = await getHotRecord(id)
    record.value = res.data
  } finally {
    loading.value = false
  }
})
</script>
