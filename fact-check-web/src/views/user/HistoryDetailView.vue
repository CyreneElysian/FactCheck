<template>
  <el-card v-loading="loading">
    <template #header>
      <div style="display: flex; align-items: center; gap: 12px">
        <span>检测详情 #{{ id }}</span>
        <el-tag
          v-if="record.status === 2"
          :type="record.verdict === 1 ? 'success' : record.verdict === 2 ? 'danger' : 'info'"
        >
          {{ verdictText(record.verdict) }}
        </el-tag>
        <el-tag v-else-if="record.status === 1" type="warning">待人工诊断</el-tag>
        <el-tag v-else type="info">检测中</el-tag>
      </div>
    </template>

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
    <p v-if="record.imageUrl"><el-image :src="record.imageUrl" style="max-width: 300px" fit="contain" /></p>

    <div v-if="record.reasoningChain" style="margin-top: 12px">
      <b>推理链：</b>
      <pre style="white-space: pre-wrap">{{ record.reasoningChain }}</pre>
    </div>

    <el-table
      v-if="record.evidenceList && record.evidenceList.length"
      :data="record.evidenceList"
      style="margin-top: 12px"
    >
      <el-table-column prop="title" label="证据标题" />
      <el-table-column prop="source" label="来源" width="120" />
      <el-table-column prop="sourceCredibility" label="可信度" width="90" />
      <el-table-column prop="relevanceScore" label="相关度" width="90" />
    </el-table>

    <div v-if="record.status === 2" style="margin-top: 16px">
      <el-button type="warning" @click="appealVisible = true">申诉/纠错</el-button>
      <!-- P3-37：按后端返回的 favorited 回显状态，并支持取消收藏（原先按钮永远显示"收藏"） -->
      <el-button :type="record.favorited ? 'info' : 'primary'" @click="onToggleFavorite">
        {{ record.favorited ? '取消收藏' : '收藏' }}
      </el-button>
      <el-button type="success" @click="onShare">分享</el-button>
      <el-button type="info" @click="openHistory">历史版本</el-button>
      <!-- P3-37：评分回显历史值（后端 myRating），可随时修改 -->
      <el-button :type="rating === 1 ? 'success' : 'default'" @click="onRate(1)">有帮助</el-button>
      <el-button :type="rating === 0 ? 'danger' : 'default'" @click="onRate(0)">无帮助</el-button>
      <span v-if="rating !== null" style="margin-left: 8px; color: #909399; font-size: 12px">
        你已评分，可随时修改
      </span>
    </div>

    <el-dialog v-model="appealVisible" title="申诉/纠错" width="480px">
      <el-form>
        <el-form-item label="申诉类型">
          <el-select v-model="appealForm.reasonType" style="width: 100%">
            <el-option label="结论错误" :value="1" />
            <el-option label="证据不足" :value="2" />
            <el-option label="来源不可信" :value="3" />
            <el-option label="其他" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="申诉说明">
          <el-input v-model="appealForm.content" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="appealVisible = false">取消</el-button>
        <el-button type="primary" @click="onAppeal">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shareVisible" title="分享卡片" width="660px">
      <canvas ref="shareCanvas" width="600" height="400" style="width: 100%; border-radius: 8px"></canvas>
      <template #footer>
        <el-button @click="shareVisible = false">关闭</el-button>
        <el-button type="primary" @click="downloadShare">下载图片</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="historyVisible" title="结论修订历史" width="720px">
      <el-table v-loading="historyLoading" :data="historyRecords">
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
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getDetectRecord, getRecordRevisions } from '@/api/detect'
import { submitAppeal } from '@/api/appeal'
import { addFavorite, removeFavorite } from '@/api/favorite'
import { submitRating } from '@/api/rating'

const route = useRoute()
const id = route.params.id as string
const loading = ref(false)
const record = ref<any>({})
const appealVisible = ref(false)
const shareVisible = ref(false)
const shareCanvas = ref<HTMLCanvasElement | null>(null)
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyRecords = ref<any[]>([])
const rating = ref<number | null>(null)
const appealForm = reactive({ reasonType: 1, content: '' })

const verdictText = (v: number) => (v === 1 ? '真' : v === 2 ? '假' : '无法判定')

const load = async () => {
  loading.value = true
  try {
    const res: any = await getDetectRecord(id)
    record.value = res.data
    // P3-37：回显历史评分（未评过为 null）
    rating.value = res.data.myRating === undefined ? null : res.data.myRating
  } finally {
    loading.value = false
  }
}

/** 收藏 / 取消收藏（P3-37） */
const onToggleFavorite = async () => {
  if (record.value.favorited) {
    await removeFavorite(id)
    record.value.favorited = false
    ElMessage.success('已取消收藏')
  } else {
    await addFavorite(id)
    record.value.favorited = true
    ElMessage.success('已收藏')
  }
}

const onRate = async (value: number) => {
  await submitRating({ recordId: id, rating: value })
  rating.value = value
  ElMessage.success('已提交评分')
}

const onAppeal = async () => {
  await submitAppeal({ recordId: id, ...appealForm })
  ElMessage.success('申诉已提交')
  appealVisible.value = false
}

const openHistory = async () => {
  historyVisible.value = true
  historyLoading.value = true
  try {
    const res: any = await getRecordRevisions(id)
    historyRecords.value = res.data
  } finally {
    historyLoading.value = false
  }
}

const onShare = async () => {
  shareVisible.value = true
  await nextTick()
  drawShareCard()
}

const drawShareCard = () => {
  const canvas = shareCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = '#f5f7fa'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(20, 20, canvas.width - 40, canvas.height - 40)

  ctx.fillStyle = '#409eff'
  ctx.font = 'bold 28px sans-serif'
  ctx.textAlign = 'center'
  ctx.fillText('事实检测结果', canvas.width / 2, 70)

  const verdict = verdictText(record.value.verdict)
  const verdictColor =
    record.value.verdict === 1 ? '#67c23a' : record.value.verdict === 2 ? '#f56c6c' : '#909399'
  ctx.fillStyle = verdictColor
  ctx.font = 'bold 48px sans-serif'
  ctx.fillText(verdict, canvas.width / 2, 145)

  ctx.fillStyle = '#303133'
  ctx.font = '18px sans-serif'
  ctx.textAlign = 'left'
  wrapText(ctx, record.value.content || '', 50, 200, canvas.width - 100, 30, 5)

  // 记录链接（P3-38 的降级方案）
  //
  // 产品文档要求分享卡片含二维码；离线环境装不了 qrcode 依赖（见改进清单 P3-38），
  // 这里先把可访问链接打印在卡片上——对方手动输入也能打开，聊胜于无。
  ctx.fillStyle = '#409eff'
  ctx.font = '14px sans-serif'
  ctx.textAlign = 'center'
  ctx.fillText(shareUrl.value, canvas.width / 2, 330)

  ctx.fillStyle = '#909399'
  ctx.font = '14px sans-serif'
  ctx.textAlign = 'center'
  ctx.fillText('来自 事实检测平台', canvas.width / 2, 360)
}

/** 分享卡片上的记录链接 */
const shareUrl = computed(() => `${window.location.origin}/history/${id}`)

const wrapText = (
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  maxWidth: number,
  lineHeight: number,
  maxLines: number
) => {
  const chars = Array.from(text)
  let line = ''
  let lineCount = 0
  for (let i = 0; i < chars.length; i++) {
    const testLine = line + chars[i]
    if (ctx.measureText(testLine).width > maxWidth && line) {
      ctx.fillText(line, x, y + lineCount * lineHeight)
      line = chars[i]
      lineCount++
      if (lineCount >= maxLines) {
        ctx.fillText(line.slice(0, line.length - 1) + '…', x, y + (lineCount - 1) * lineHeight)
        return
      }
    } else {
      line = testLine
    }
  }
  ctx.fillText(line, x, y + lineCount * lineHeight)
}

const downloadShare = () => {
  const canvas = shareCanvas.value
  if (!canvas) return
  const link = document.createElement('a')
  link.href = canvas.toDataURL('image/png')
  link.download = 'fact-check-share.png'
  link.click()
  ElMessage.success('分享图片已下载')
}

onMounted(load)
</script>
