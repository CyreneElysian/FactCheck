<template>
  <el-container class="user-layout">
    <el-header class="header">
      <div class="logo" @click="$router.push('/')">事实检测</div>
      <el-menu mode="horizontal" :default-active="$route.path" router :ellipsis="false" class="menu">
        <el-menu-item index="/">检测</el-menu-item>
        <el-menu-item index="/history">历史</el-menu-item>
        <el-menu-item index="/hot">热门 Top10</el-menu-item>
        <el-menu-item index="/authority">权威信息</el-menu-item>
      </el-menu>
      <div class="right">
        <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
          <el-button link type="primary" @click="$router.push('/messages')">站内信</el-button>
        </el-badge>
        <el-dropdown>
          <span class="avatar-wrap">
            <el-avatar :src="avatar" :size="36">{{ nickname.charAt(0) }}</el-avatar>
            <span class="nickname">{{ nickname }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="$router.push('/profile')">个人中心</el-dropdown-item>
              <el-dropdown-item @click="$router.push('/favorites')">我的收藏</el-dropdown-item>
              <el-dropdown-item @click="$router.push('/appeals')">我的申诉</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>
    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { getUnreadCount } from '@/api/message'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const nickname = computed(() => userStore.userInfo?.nickname || '用户')
const avatar = computed(() => userStore.userInfo?.avatar || '')
const unreadCount = ref(0)

const loadUnread = async () => {
  try {
    const res: any = await getUnreadCount()
    unreadCount.value = res.data || 0
  } catch (e) {
    // 未登录或接口异常时忽略红点
  }
}

watch(
  () => route.path,
  () => {
    if (userStore.token) {
      loadUnread()
    }
  }
)

onMounted(() => {
  if (userStore.token) {
    loadUnread()
  }
})

const logout = () => {
  userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.user-layout {
  min-height: 100vh;
  background: #f5f7fa;
}
.header {
  display: flex;
  align-items: center;
  gap: 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.logo {
  font-size: 20px;
  font-weight: bold;
  color: #409eff;
  cursor: pointer;
}
.menu {
  flex: 1;
}
.right {
  display: flex;
  align-items: center;
  gap: 12px;
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
}
</style>
