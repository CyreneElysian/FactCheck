<template>
  <el-card v-loading="loading">
    <h2>{{ detail.title }}</h2>
    <p><b>分类：</b>{{ detail.category }}　<b>来源：</b>{{ detail.source }}</p>
    <p v-if="detail.publishTime"><b>发布时间：</b>{{ detail.publishTime }}</p>
    <el-divider />
    <div style="white-space: pre-wrap">{{ detail.content }}</div>
    <p v-if="validSourceUrl" style="margin-top: 16px">
      <el-link type="primary" :href="detail.sourceUrl" target="_blank">查看原文</el-link>
    </p>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getAuthority } from '@/api/authority'

const route = useRoute()
const loading = ref(false)
const detail = ref<any>({})
const validSourceUrl = computed(() => {
  const url = detail.value.sourceUrl
  return typeof url === 'string' && /^https?:\/\//i.test(url)
})

onMounted(async () => {
  loading.value = true
  try {
    const res: any = await getAuthority(route.params.id as string)
    detail.value = res.data
  } finally {
    loading.value = false
  }
})
</script>
