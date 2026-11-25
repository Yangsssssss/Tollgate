<template>
  <div>
    <el-radio-group v-model="days" @change="load">
      <el-radio-button :value="1">今天</el-radio-button>
      <el-radio-button :value="7">近 7 天</el-radio-button>
      <el-radio-button :value="30">近 30 天</el-radio-button>
    </el-radio-group>

    <el-descriptions :column="3" border style="margin-top: 16px">
      <el-descriptions-item label="调用次数">{{ fmtTokens(report?.calls) }}</el-descriptions-item>
      <el-descriptions-item label="总 tokens">{{ fmtTokens(report?.tokens) }}</el-descriptions-item>
      <el-descriptions-item label="总消费">{{ fmtMoney(report?.costMicros) }}</el-descriptions-item>
    </el-descriptions>

    <el-card style="margin-top: 16px">
      <template #header>每日 tokens</template>
      <div ref="chartEl" style="height: 280px"></div>
    </el-card>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="8">
        <el-card><template #header>按模型</template>
          <el-table :data="report?.byModel ?? []" size="small" stripe>
            <el-table-column prop="modelAlias" label="模型" />
            <el-table-column prop="calls" label="次数" width="70" />
            <el-table-column label="费用" width="100">
              <template #default="{ row }">{{ fmtMoney(row.costMicros) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card><template #header>余额流水</template>
          <el-table :data="ledger" size="small" stripe>
            <el-table-column prop="created_at" label="时间" width="180" />
            <el-table-column prop="reason" label="类型" width="130" />
            <el-table-column label="变动" width="120">
              <template #default="{ row }">
                <span :style="{ color: row.delta_micros >= 0 ? '#3B6D11' : '#A32D2D' }">
                  {{ row.delta_micros >= 0 ? '+' : '' }}{{ fmtMoney(row.delta_micros) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="变动后余额">
              <template #default="{ row }">{{ fmtMoney(row.balance_after_micros) }}</template>
            </el-table-column>
            <el-table-column prop="ref" label="引用" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { api, fmtMoney, fmtTokens, type UsageReport } from '../api'

const days = ref(7)
const report = ref<UsageReport>()
const ledger = ref<Record<string, any>[]>([])
const chartEl = ref<HTMLElement>()
let chart: echarts.ECharts | undefined

const load = async () => {
  report.value = (await api.usage(days.value)).data.report
  ledger.value = (await api.ledger()).data.ledger
  chart?.setOption({
    xAxis: { type: 'category', data: report.value.daily.map(d => d.date) },
    yAxis: { type: 'value' },
    tooltip: { trigger: 'axis' },
    series: [{ type: 'bar', data: report.value.daily.map(d => d.tokens), name: 'tokens' }]
  })
}

onMounted(async () => {
  chart = echarts.init(chartEl.value!)
  await load()
})

onBeforeUnmount(() => chart?.dispose())
</script>
