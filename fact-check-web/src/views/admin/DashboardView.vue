<template>
  <el-row :gutter="16">
    <el-col v-for="item in cards" :key="item.label" :span="4">
      <el-card>
        <div class="num">{{ item.value }}</div>
        <div class="label">{{ item.label }}</div>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getStatsOverview } from '@/api/admin'

const stats = ref<any>({})
const cards = computed(() => [
  { label: '今日检测量', value: stats.value.todayDetectCount ?? 0 },
  { label: 'AI 判定数', value: stats.value.todayAiCount ?? 0 },
  { label: '人工诊断数', value: stats.value.todayManualCount ?? 0 },
  { label: '待诊断数', value: stats.value.pendingCount ?? 0 },
  { label: '待处理申诉', value: stats.value.pendingAppealCount ?? 0 },
  { label: '注册用户数', value: stats.value.totalUserCount ?? 0 }
])

onMounted(async () => {
  const res: any = await getStatsOverview()
  stats.value = res.data
})
</script>

<style scoped>
.num {
  font-size: 28px;
  font-weight: bold;
  color: #409eff;
}
.label {
  margin-top: 8px;
  color: #909399;
}
</style>
