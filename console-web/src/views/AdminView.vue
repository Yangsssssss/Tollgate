<template>
  <div>
    <el-button type="primary" :loading="refreshing" @click="refresh">刷新配置快照</el-button>
    <span v-if="lastRefresh" style="margin-left: 12px; color: #888; font-size: 13px">{{ lastRefresh }}</span>
    <el-alert type="info" :closable="false" show-icon style="margin-top: 12px"
      title="管理台操作（车道/密钥/路由/费率）写库后，需等快照刷新（30 秒周期）或点上方按钮立即生效" />

    <el-card style="margin-top: 16px">
      <template #header>车道（lane）与熔断状态</template>
      <el-table :data="lanes" size="small" stripe>
        <el-table-column prop="code" label="编码" width="150" />
        <el-table-column prop="base_url" label="Base URL" />
        <el-table-column prop="protocol" label="协议" width="170" />
        <el-table-column prop="priority" label="优先级" width="80" />
        <el-table-column prop="weight" label="权重" width="70" />
        <el-table-column prop="active_keys" label="密钥数" width="80" />
        <el-table-column label="熔断状态" width="120">
          <template #default="{ row }">
            <el-tag :type="tagType(row.breaker?.state)">{{ row.breaker?.state ?? '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="失败率" width="80">
          <template #default="{ row }">{{ row.breaker?.failureRate ?? '-' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header>最近调用（request_log 近 100 条）</template>
      <el-table :data="recent" size="small" stripe>
        <el-table-column prop="created_at" label="时间" width="180" />
        <el-table-column prop="trace_id" label="Trace" width="200" />
        <el-table-column prop="lane_code" label="车道" width="140" />
        <el-table-column prop="attempt" label="尝试" width="60" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="http_status" label="HTTP" width="70" />
        <el-table-column prop="error_code" label="错误码" width="160" />
        <el-table-column prop="latency_millis" label="耗时(ms)" width="90" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const lanes = ref<Record<string, any>[]>([])
const recent = ref<Record<string, any>[]>([])
const refreshing = ref(false)
const lastRefresh = ref('')

const tagType = (state?: string) =>
  state === 'CLOSED' ? 'success' : state === 'OPEN' ? 'danger' : state === 'HALF_OPEN' ? 'warning' : 'info'

const load = async () => {
  lanes.value = (await api.adminLanes()).data.lanes ?? []
  recent.value = (await api.adminRecent()).data.logs ?? []
}

onMounted(load)

const refresh = async () => {
  refreshing.value = true
  try {
    const resp = await api.adminRefresh()
    const d = resp.data
    lastRefresh.value = `lanes=${d.lanes} models=${d.models} credentials=${d.credentials} laneKeys=${d.laneKeys}`
    await load()
    ElMessage.success('快照已刷新')
  } finally {
    refreshing.value = false
  }
}
</script>
