<template>
  <el-container style="min-height: 100vh">
    <el-aside width="200px" class="aside">
      <div class="logo">🛣️ Tollgate</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/">总览</el-menu-item>
        <el-menu-item index="/keys">API 密钥</el-menu-item>
        <el-menu-item index="/usage">用量明细</el-menu-item>
        <el-menu-item index="/pricing">价目表</el-menu-item>
        <el-menu-item index="/admin">管理台</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>演示租户：{{ username }}</span>
        <span>余额：<b>{{ balanceText }}</b></span>
        <el-button size="small" text type="danger" @click="logout">退出</el-button>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, fmtMoney } from '../api'

const router = useRouter()
const username = localStorage.getItem('tg_username') ?? '(未知)'
const balanceText = ref('…')

const loadBalance = async () => {
  try {
    const resp = await api.me()
    balanceText.value = fmtMoney(resp.data.balanceMicros)
  } catch { /* 拦截器处理 401 */ }
}

onMounted(loadBalance)
setInterval(loadBalance, 10000)

const logout = () => {
  localStorage.removeItem('tg_token')
  router.push('/login')
}
</script>

<style scoped>
.aside { background: #fff; border-right: 1px solid #eee; }
.logo { font-weight: 600; padding: 18px 20px; font-size: 16px; }
.header {
  display: flex; align-items: center; gap: 20px;
  border-bottom: 1px solid #eee; background: #fff;
}
</style>
