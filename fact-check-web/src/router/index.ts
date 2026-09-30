import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import UserLayout from '@/layouts/UserLayout.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'

const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录' } },
  { path: '/register', component: () => import('@/views/RegisterView.vue'), meta: { title: '注册' } },
  {
    path: '/',
    component: UserLayout,
    children: [
      { path: '', component: () => import('@/views/user/DetectView.vue'), meta: { title: '事实检测' } },
      { path: 'history', component: () => import('@/views/user/HistoryView.vue'), meta: { title: '历史记录' } },
      { path: 'history/:id', component: () => import('@/views/user/HistoryDetailView.vue'), meta: { title: '检测详情' } },
      { path: 'hot', component: () => import('@/views/user/HotView.vue'), meta: { title: '热门 Top10' } },
      { path: 'authority', component: () => import('@/views/user/AuthorityView.vue'), meta: { title: '权威信息' } },
      { path: 'authority/:id', component: () => import('@/views/user/AuthorityDetailView.vue'), meta: { title: '权威信息详情' } },
      { path: 'appeals', component: () => import('@/views/user/AppealsView.vue'), meta: { title: '我的申诉' } },
      { path: 'favorites', component: () => import('@/views/user/FavoritesView.vue'), meta: { title: '我的收藏' } },
      { path: 'messages', component: () => import('@/views/user/MessagesView.vue'), meta: { title: '站内信' } },
      { path: 'profile', component: () => import('@/views/user/ProfileView.vue'), meta: { title: '个人中心' } }
    ]
  },
  {
    path: '/admin',
    component: AdminLayout,
    meta: { requiresAdmin: true },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', component: () => import('@/views/admin/DashboardView.vue'), meta: { title: '工作台' } },
      { path: 'pending', component: () => import('@/views/admin/PendingView.vue'), meta: { title: '待诊断 Top10' } },
      { path: 'pending/:id', component: () => import('@/views/admin/PendingDetailView.vue'), meta: { title: '诊断工作台' } },
      { path: 'records', component: () => import('@/views/admin/RecordsView.vue'), meta: { title: '结论管理' } },
      { path: 'appeals', component: () => import('@/views/admin/AppealsView.vue'), meta: { title: '申诉处理' } },
      { path: 'authority', component: () => import('@/views/admin/AuthorityManageView.vue'), meta: { title: '权威信息管理' } },
      { path: 'accounts', component: () => import('@/views/admin/AccountsView.vue'), meta: { title: '账号管理' } },
      { path: 'logs', component: () => import('@/views/admin/LogsView.vue'), meta: { title: '操作日志' } },
      { path: 'profile', component: () => import('@/views/admin/ProfileView.vue'), meta: { title: '个人中心' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  document.title = (to.meta.title as string) || '事实检测'
  const token = localStorage.getItem('token')
  const role = Number(localStorage.getItem('role') || 0)
  if (to.path !== '/login' && to.path !== '/register' && !token) {
    next('/login')
  } else if (to.path.startsWith('/admin') && role !== 1) {
    next('/')
  } else {
    next()
  }
})

export default router
