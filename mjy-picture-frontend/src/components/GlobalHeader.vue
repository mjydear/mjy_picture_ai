<template>
  <div id="globalHeader">
    <a-row :wrap="false">
      <a-col flex="200px">
        <router-link to="/">
          <div class="title-bar">
            <img class="logo" src="../assets/logo.png" alt="logo" />
            <div class="title-text">
              <div class="title">云图库</div>
              <div class="subtitle">CLOUD GALLERY</div>
            </div>
          </div>
        </router-link>
      </a-col>
      <a-col flex="auto">
        <a-menu
          v-model:selectedKeys="current"
          mode="horizontal"
          :items="items"
          @click="doMenuClick"
        />
      </a-col>
      <!-- 用户信息展示栏 -->
      <a-col flex="120px">
        <div class="user-login-status">
          <div v-if="loginUserStore.loginUser.id">
            <a-dropdown>
              <a-space>
                <a-avatar :src="loginUserStore.loginUser.userAvatar" />
                {{ loginUserStore.loginUser.userName ?? '无名' }}
              </a-space>
              <template #overlay>
                <a-menu>
                  <a-menu-item>
                    <router-link to="/my_space">
                      <UserOutlined />
                      我的空间
                    </router-link>
                  </a-menu-item>
                  <a-menu-item @click="doLogout">
                    <LogoutOutlined />
                    退出登录
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
          <div v-else>
            <a-button type="primary" href="/user/login">登录</a-button>
          </div>
        </div>
      </a-col>
    </a-row>
  </div>
</template>
<script lang="ts" setup>
import { computed, h, ref } from 'vue'
import { HomeOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons-vue'
import { MenuProps, message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { userLogoutUsingPost } from '@/api/userController.ts'

const loginUserStore = useLoginUserStore()

// 未经过滤的菜单项
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  {
    key: '/add_picture',
    label: '创建图片',
    title: '创建图片',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: '/admin/pictureManage',
    label: '图片管理',
    title: '图片管理',
  },
  {
    key: '/admin/spaceManage',
    label: '空间管理',
    title: '空间管理',
  },
]

// 根据权限过滤菜单项
const filterMenus = (menus = [] as MenuProps['items']) => {
  return menus?.filter((menu) => {
    // 管理员才能看到 /admin 开头的菜单
    if (menu?.key?.startsWith('/admin')) {
      const loginUser = loginUserStore.loginUser
      if (!loginUser || loginUser.userRole !== 'admin') {
        return false
      }
    }
    return true
  })
}

// 展示在菜单的路由数组
const items = computed(() => filterMenus(originItems))

const router = useRouter()
// 当前要高亮的菜单项
const current = ref<string[]>([])
// 监听路由变化，更新高亮菜单项
router.afterEach((to, from, next) => {
  current.value = [to.path]
})

// 路由跳转事件
const doMenuClick = ({ key }) => {
  router.push({
    path: key,
  })
}

// 用户注销
const doLogout = async () => {
  const res = await userLogoutUsingPost()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
    await router.push('/user/login')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
#globalHeader .title-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  transition: transform 0.25s ease;
}

#globalHeader .title-bar:hover {
  transform: translateY(-1px);
}

.logo {
  height: 42px;
  width: 42px;
  border-radius: 12px;
  object-fit: cover;
  box-shadow: 0 6px 16px rgba(99, 102, 241, 0.28);
}

.title-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.title {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 1px;
  background: linear-gradient(120deg, #4f46e5, #8b5cf6 45%, #06b6d4);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.subtitle {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 3px;
  color: #b3b6c4;
  margin-top: 2px;
}

/* 顶部菜单美化 */
#globalHeader :deep(.ant-menu-horizontal) {
  border-bottom: none;
  background: transparent;
  font-weight: 500;
}

#globalHeader :deep(.ant-menu-horizontal .ant-menu-item) {
  border-radius: 10px;
  margin-inline: 4px;
  transition: all 0.2s ease;
}

#globalHeader :deep(.ant-menu-horizontal .ant-menu-item::after) {
  display: none !important;
}

#globalHeader :deep(.ant-menu-horizontal .ant-menu-item:hover) {
  color: #4f46e5;
  background: rgba(99, 102, 241, 0.08);
}

#globalHeader :deep(.ant-menu-horizontal .ant-menu-item-selected) {
  color: #4f46e5;
  background: linear-gradient(120deg, rgba(79, 70, 229, 0.12), rgba(139, 92, 246, 0.12));
}

.user-login-status {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
}

.user-login-status :deep(.ant-btn-primary) {
  border: none;
  border-radius: 10px;
  padding-inline: 22px;
  background: linear-gradient(120deg, #4f46e5, #8b5cf6);
  box-shadow: 0 6px 16px rgba(99, 102, 241, 0.32);
}
</style>
