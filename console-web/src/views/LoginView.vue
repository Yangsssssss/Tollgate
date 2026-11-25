<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2 class="brand">🛣️ Tollgate 控制台</h2>
      <p class="sub">大模型收费站 · 统一闸口、渠道调度、token 计量</p>
      <el-tabs v-model="tab">
        <el-tab-pane label="登录" name="login">
          <el-form @submit.prevent>
            <el-form-item>
              <el-input v-model="username" placeholder="用户名" />
            </el-form-item>
            <el-form-item>
              <el-input v-model="password" type="password" placeholder="密码" show-password />
            </el-form-item>
            <el-button type="primary" style="width: 100%" :loading="busy" @click="doLogin">登录</el-button>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="注册" name="register">
          <el-form @submit.prevent>
            <el-form-item>
              <el-input v-model="username" placeholder="用户名（3-32 位字母数字下划线）" />
            </el-form-item>
            <el-form-item>
              <el-input v-model="password" type="password" placeholder="密码（至少 6 位）" show-password />
            </el-form-item>
            <el-alert type="info" :closable="false" show-icon
              title="注册即创建独立租户，赠送 ¥5.00 演示额度" style="margin-bottom: 12px" />
            <el-button type="primary" style="width: 100%" :loading="busy" @click="doRegister">注册并登录</el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api'

const router = useRouter()
const tab = ref('login')
const username = ref('')
const password = ref('')
const busy = ref(false)

async function submit(path: string, okMsg: string) {
  busy.value = true
  try {
    const resp = await http.post(`/api/auth/${path}`, {
      username: username.value, password: password.value
    })
    localStorage.setItem('tg_token', resp.data.token)
    localStorage.setItem('tg_username', resp.data.username)
    ElMessage.success(okMsg)
    router.push('/')
  } catch (e: any) {
    ElMessage.error(e.response?.data?.error?.message ?? '请求失败')
  } finally {
    busy.value = false
  }
}

const doLogin = () => submit('login', '登录成功')
const doRegister = () => submit('register', '注册成功，已赠送 ¥5.00')
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-card { width: 380px; }
.brand { margin: 0 0 4px; }
.sub { color: #888; font-size: 13px; margin: 0 0 16px; }
</style>
