import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'
import { authState, initializeAuth } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
    },
    {
      path: '/console',
      name: 'console',
      component: () => import('@/pages/console/ConsolePage.vue'),
      redirect: '/console/chat',
      meta: { requiresAuth: true },
      children: [
        {
          path: 'chat/:sessionId?',
          name: 'chat',
          component: () => import('@/pages/console/ChatPage.vue'),
        },
        {
          path: 'sessions',
          name: 'sessions',
          component: () => import('@/pages/console/SessionsPage.vue'),
        },
        {
          path: 'devices',
          name: 'devices',
          component: () => import('@/pages/console/DevicesPage.vue'),
        },
      ],
    },
    {
      path: '/about',
      name: 'about',
      component: () => import('@/pages/AboutPage.vue'),
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/auth/LoginPage.vue'),
      meta: { guestOnly: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/pages/auth/RegisterPage.vue'),
      meta: { guestOnly: true },
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/pages/user/ProfilePage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/pages/admin/UsersPage.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/403',
      name: 'forbidden',
      component: () => import('@/pages/errors/ForbiddenPage.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  await initializeAuth()

  if (to.meta.guestOnly && authState.isAuthenticated.value) {
    return { name: 'console' }
  }

  if (to.meta.requiresAuth && !authState.isAuthenticated.value) {
    return {
      name: 'login',
      query: { redirect: to.fullPath },
    }
  }

  if (to.meta.requiresAdmin && !authState.isAdmin.value) {
    return { name: 'forbidden' }
  }
})

export default router
