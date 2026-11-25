import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue') },
    {
      path: '/',
      component: () => import('./views/Layout.vue'),
      children: [
        { path: '', component: () => import('./views/DashboardView.vue') },
        { path: 'keys', component: () => import('./views/KeysView.vue') },
        { path: 'usage', component: () => import('./views/UsageView.vue') },
        { path: 'pricing', component: () => import('./views/PricingView.vue') },
        { path: 'admin', component: () => import('./views/AdminView.vue') }
      ]
    }
  ]
})

// 未登录一律踢回登录页
router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('tg_token')) {
    return '/login'
  }
})

export default router
