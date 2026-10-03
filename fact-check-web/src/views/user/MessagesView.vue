<template>
  <el-card>
    <template #header>
      <div style="display: flex; justify-content: space-between">
        <span>站内信</span>
        <el-button type="primary" link @click="onReadAll">全部已读</el-button>
      </div>
    </template>
    <el-table v-loading="loading" :data="records">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeText(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" show-overflow-tooltip />
      <el-table-column prop="content" label="内容" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.isRead === 1 ? 'info' : 'danger'">{{
            row.isRead === 1 ? '已读' : '未读'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="时间" width="180" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button v-if="row.relatedId" type="primary" link @click="goRecord(row.relatedId)"
            >查看关联记录</el-button
          >
          <el-button v-if="row.isRead === 0" type="primary" link @click="onRead(row.id)">标记已读</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- P3-36：原先固定只取前 20 条且无分页 -->
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
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { pageMessages, readAllMessages, readMessage } from '@/api/message'

const router = useRouter()
const loading = ref(false)
const records = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 10 })

/** 站内信类型文案（与后端 message.type 约定一致） */
const typeText = (value: number) =>
  value === 2
    ? 'AI 判定'
    : value === 3
      ? '人工诊断'
      : value === 4
        ? '申诉结果'
        : value === 5
          ? '系统通知'
          : value === 6
            ? '检测失败'
            : '通知'

const load = async () => {
  loading.value = true
  try {
    const res: any = await pageMessages(query)
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
const onSizeChange = (size: number) => {
  query.pageSize = size
  query.page = 1
  load()
}
const onRead = async (id: number) => {
  await readMessage(id)
  ElMessage.success('已标记已读')
  load()
}
const onReadAll = async () => {
  await readAllMessages()
  ElMessage.success('已全部已读')
  load()
}
const goRecord = (recordId: number) => router.push(`/history/${recordId}`)
onMounted(load)
</script>
