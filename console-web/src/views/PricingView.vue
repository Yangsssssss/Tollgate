<template>
  <el-card>
    <template #header>模型价目（每 1K token，单位：元）</template>
    <el-table :data="tariffs" stripe>
      <el-table-column prop="model_alias" label="模型" width="180" />
      <el-table-column label="输入 ¥/1K">
        <template #default="{ row }">{{ microToYuan(row.prompt_micros_per_1k) }}</template>
      </el-table-column>
      <el-table-column label="输出 ¥/1K">
        <template #default="{ row }">{{ microToYuan(row.completion_micros_per_1k) }}</template>
      </el-table-column>
    </el-table>
    <p class="note">计费按落账时刻的费率快照计算，调价不回溯历史账单。</p>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'

const tariffs = ref<Record<string, any>[]>([])
const microToYuan = (m: number) => (m / 1e6).toFixed(6).replace(/0+$/, '').replace(/\.$/, '')

onMounted(async () => { tariffs.value = (await api.tariffs()).data.tariffs })
</script>

<style scoped>
.note { color: #999; font-size: 12px; }
</style>
