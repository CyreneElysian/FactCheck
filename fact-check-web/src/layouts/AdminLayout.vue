<template>
  <el-container class="admin-layout">
    <el-aside width="220px" class="aside">
      <div class="logo">事实检测·管理端</div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#001529"
        text-color="#fff"
        active-text-color="#409eff"
      >
        <el-menu-item index="/admin/dashboard">工作台</el-menu-item>
        <el-menu-item index="/admin/pending">待诊断 Top10</el-menu-item>
        <el-menu-item index="/admin/records">结论管理</el-menu-item>
        <el-menu-item index="/admin/appeals">申诉处理</el-menu-item>
        <el-menu-item index="/admin/authority">权威信息管理</el-menu-item>
        <el-menu-item index="/admin/accounts">账号管理</el-menu-item>
        <el-menu-item index="/admin/logs">操作日志</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ $route.meta.title }}</span>
        <el-dropdown>
          <span class="avatar-wrap">
            <el-avatar :size="36">{{ nickname.charAt(0) }}</el-avatar>
            <span class="nickname">{{ nickname }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="$router.push('/admin/profile')">个人中心</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const nickname = computed(() => userStore.userInfo?.nickname || '管理员')

const logout = () => {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}
.aside {
  background: #001529;
}
.logo {
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  padding: 18px 16px;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.avatar-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.nickname {
  font-size: 14px;
}
.main {
  padding: 24px;
  background: #f5f7fa;
}
</style>
