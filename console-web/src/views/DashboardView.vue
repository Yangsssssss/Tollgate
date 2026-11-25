<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6"><el-card><div class="stat-label">余额</div><div class="stat-value">{{ fmtMoney(me?.balanceMicros) }}</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="stat-label">近 7 天调用</div><div class="stat-value">{{ fmtTokens(report?.calls) }}</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="stat-label">近 7 天 tokens</div><div class="stat-value">{{ fmtTokens(report?.tokens) }}</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="stat-label">近 7 天消费</div><div class="stat-value">{{ fmtMoney(report?.costMicros) }}</div></el-card></el-col>
    </el-row>

    <el-card style="margin-top: 16px">
      <template #header>近 7 天每日消费</template>
      <div ref="chartEl" style="height: 300px"></div>
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header>最近调用</template>
      <el-table :data="report?.recent ?? []" size="small" stripe>
        <el-table-column prop="occurred_at" label="时间" width="180" />
        <el-table-column prop="model_alias" label="模型" width="140" />
        <el-table-column prop="lane_code" label="车道" width="140" />
        <el-table-column prop="prompt_tokens" label="Prompt" width="90" />
        <el-table-column prop="completion_tokens" label="Completion" width="110" />
        <el-table-column label="费用" width="100">
          <template #default="{ row }">{{ fmtMoney(row.cost_micros) }}</template>
        </el-table-column>
        <el-table-column prop="latency_millis" label="耗时(ms)" width="90" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { api, fmtMoney, fmtTokens, type MeInfo, type UsageReport } from '../api'

const me = ref<MeInfo>()
const report = ref<UsageReport>()
const chartEl = ref<HTMLElement>()
let chart: echarts.ECharts | undefined

onMounted(async () => {
  me.value = (await api.me()).data
  report.value = (await api.usage(7)).data.report
  chart = echarts.init(chartEl.value!)
  chart.setOption({
    xAxis: { type: 'category', data: report.value.daily.map(d => d.date) },
    yAxis: { type: 'value' },
    tooltip: { trigger: 'axis' },
    series: [{
      type: 'bar',
      data: report.value.daily.map(d => +(d.costMicros / 1e6).toFixed(4)),
      name: '消费(元)'
    }]
  })
})

onBeforeUnmount(() => chart?.dispose())
</script>

<style scoped>
.stat-label { color: #888; font-size: 13px; }
.stat-value { font-size: 22px; font-weight: 600; margin-top: 6px; }
</style>
