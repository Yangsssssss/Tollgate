<template>
  <div>
    <el-button type="primary" @click="dialog = true">生成新密钥</el-button>
    <el-alert v-if="plaintext" type="warning" :closable="false" show-icon style="margin-top: 12px">
      <template #title>密钥明文（仅此一次展示，请立即保存）</template>
      <code style="font-size: 15px">{{ plaintext }}</code>
    </el-alert>

    <el-table :data="keys" style="margin-top: 16px" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="key_prefix" label="前缀" width="140" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="200" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-popconfirm title="吊销后调用立即 401（快照刷新后生效），确认？" @confirm="disable(row.id)">
            <template #reference>
              <el-button size="small" type="danger" text :disabled="row.status !== 'ACTIVE'">吊销</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" title="生成新密钥" width="420">
      <el-input v-model="newName" placeholder="密钥名称，如：我的应用-生产" />
      <p class="hint">新密钥约 30 秒内被数据面接受（快照刷新周期），也可在管理台手动 refresh 立即生效。</p>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="create">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const keys = ref<Record<string, any>[]>([])
const dialog = ref(false)
const newName = ref('')
const plaintext = ref('')
const busy = ref(false)

const load = async () => { keys.value = (await api.keys()).data.keys }
onMounted(load)

async function create() {
  busy.value = true
  try {
    const resp = await api.createKey(newName.value)
    plaintext.value = resp.data.plaintext
    dialog.value = false
    newName.value = ''
    await load()
    ElMessage.success('密钥已生成，30 秒内生效')
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error?.message ?? '创建失败')
  } finally {
    busy.value = false
  }
}

async function disable(id: number) {
  await api.disableKey(id)
  ElMessage.success('已吊销')
  await load()
}
</script>

<style scoped>
.hint { color: #999; font-size: 12px; }
</style>
