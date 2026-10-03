<template>
  <el-card>
    <template #header>申诉处理</template>
    <!-- P3-36：补状态筛选（文档要求可按处理状态查看） -->
    <el-form inline>
      <el-form-item label="状态">
        <el-select
          v-model="query.status"
          clearable
          placeholder="全部"
          style="width: 150px"
          @change="onSearch"
        >
          <el-option label="待处理" :value="0" />
          <el-option label="已采纳" :value="1" />
          <el-option label="已驳回" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button @click="onReset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="records">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="recordId" label="记录ID" width="100" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ reasonText(row.reasonType) }}</template>
      </el-table-column>
      <el-table-column prop="content" label="申诉说明" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : row.status === 2 ? 'danger' : 'warning'">
            {{ row.status === 0 ? '待处理' : row.status === 1 ? '已采纳' : '已驳回' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="提交时间" width="180" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" type="primary" link @click="openHandle(row)">处理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- P3-36：原先声明了 total 却没有分页组件，超过一页的申诉根本看不到 -->
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

    <el-dialog v-model="handleVisible" title="处理申诉" width="560px">
      <el-form label-width="90px">
        <el-form-item label="处理方式">
          <el-radio-group v-model="handleForm.accept">
            <el-radio :label="true">采纳</el-radio>
            <el-radio :label="false">驳回</el-radio>
          </el-radio-group>
        </el-form-item>

        <!-- 采纳 = 修改结论，必须给出新结论与新的推理依据（后端会校验） -->
        <template v-if="handleForm.accept">
          <el-form-item label="新结论" required>
            <el-radio-group v-model="handleForm.newVerdict">
              <el-radio :label="1">真</el-radio>
              <el-radio :label="2">假</el-radio>
              <el-radio :label="3">无法判定</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="新推理依据" required>
            <el-input
              v-model="handleForm.newReasoningChain"
              type="textarea"
              :rows="4"
              maxlength="2000"
              show-word-limit
              placeholder="采纳申诉必须填写新的推理依据，将替换原推理链"
            />
          </el-form-item>
        </template>

        <el-form-item label="处理说明">
          <el-input
            v-model="handleForm.handleResult"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="handleForm.accept ? '选填，将作为结论修订原因留痕' : '驳回时请填写驳回原因'"
          />
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
const query = reactive({ status: undefined as number | undefined, page: 1, pageSize: 10 })
const handleVisible = ref(false)
const handleId = ref<number | null>(null)
const handleForm = reactive({
  accept: true,
  handleResult: '',
  newVerdict: null as number | null,
  newReasoningChain: ''
})

/** 申诉类型文案（与 t_appeal.reason_type 注释一致） */
const reasonText = (value: number) =>
  value === 1 ? '结论错误' : value === 2 ? '证据不足' : value === 3 ? '来源不可信' : '其他'

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

const onPageChange = (page: number) => {
  query.page = page
  load()
}
const onSizeChange = (size: number) => {
  query.pageSize = size
  query.page = 1
  load()
}
const onSearch = () => {
  query.page = 1
  load()
}
const onReset = () => {
  query.status = undefined
  query.page = 1
  load()
}

const openHandle = (row: any) => {
  handleId.value = row.id
  handleForm.accept = true
  handleForm.handleResult = ''
  handleForm.newVerdict = null
  handleForm.newReasoningChain = ''
  handleVisible.value = true
}

const onHandle = async () => {
  if (handleForm.accept) {
    if (handleForm.newVerdict === null) {
      ElMessage.warning('采纳申诉时必须选择新结论')
      return
    }
    if (!handleForm.newReasoningChain.trim()) {
      ElMessage.warning('采纳申诉时必须填写新的推理依据')
      return
    }
  } else if (!handleForm.handleResult.trim()) {
    ElMessage.warning('驳回申诉时请填写驳回原因')
    return
  }
  await handleAppeal(handleId.value!, handleForm)
  ElMessage.success(handleForm.accept ? '已采纳并修改结论' : '已驳回')
  handleVisible.value = false
  load()
}

onMounted(load)
</script>
