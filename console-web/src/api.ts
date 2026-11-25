import axios from 'axios'

/** 金额：micros（百万分之一元）→ 展示字符串 */
export function fmtMoney(micros: number | null | undefined): string {
  if (micros == null) return '¥0'
  return '¥' + (micros / 1e6).toFixed(4)
}

export function fmtTokens(n: number | null | undefined): string {
  return (n ?? 0).toLocaleString('en-US')
}

const http = axios.create({ timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('tg_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (err) => {
    if (err.response?.status === 401 && location.pathname !== '/login') {
      localStorage.removeItem('tg_token')
      location.href = '/login'
    }
    return Promise.reject(err)
  }
)

export interface MeInfo {
  userId: number
  username: string
  tenantId: number
  balanceMicros: number
}

export interface UsageReport {
  calls: number
  tokens: number
  costMicros: number
  daily: { date: string; calls: number; tokens: number; costMicros: number }[]
  byModel: { modelAlias: string; calls: number; tokens: number; costMicros: number }[]
  recent: Record<string, any>[]
}

export const api = {
  me: () => http.get<any, { data: MeInfo }>('/api/me'),
  usage: (days = 7) => http.get<any, { data: { report: UsageReport } }>('/api/usage', { params: { days } }),
  ledger: () => http.get<any, { data: { ledger: Record<string, any>[] } }>('/api/balance/ledger'),
  keys: () => http.get<any, { data: { keys: Record<string, any>[] } }>('/api/keys'),
  createKey: (name: string) => http.post<any, { data: Record<string, any> }>('/api/keys', { name }),
  disableKey: (id: number) => http.post(`/api/keys/${id}/disable`),
  tariffs: () => http.get<any, { data: { tariffs: Record<string, any>[] } }>('/api/tariffs'),
  adminLanes: () => http.get('/admin/lanes'),
  adminBreakers: () => http.get('/admin/breakers'),
  adminRecent: () => http.get('/admin/recent-requests'),
  adminRefresh: () => http.post('/admin/refresh')
}

export default http
