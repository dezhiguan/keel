<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isNavActive, NAV } from '@/router/nav'
import AgentDrawer from '@/views/agents/AgentDetail.vue'
import CommandPalette from '@/components/CommandPalette.vue'
import { useUserStore } from '@/stores/user'
import { ENV_OPTIONS, useEnvStore } from '@/stores/env'
import { useApprovalsStore } from '@/stores/approvals'
import { toKeelError } from '@/api/http'
import { clearPreviewEntered } from '@/router/redirect'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const envStore = useEnvStore()
const approvalsStore = useApprovalsStore()
const palette = ref(false)

const groups = computed(() => {
  const byGroup = new Map<string, typeof NAV>()
  for (const item of NAV) byGroup.set(item.group, [...(byGroup.get(item.group) ?? []), item])
  return [...byGroup.entries()]
})

const title = computed(() => String(route.meta.title ?? ''))
const traceId = computed(() => (route.path.startsWith('/traces/') ? String(route.params.id ?? '') : ''))
const jobCrumb = computed(() => (typeof route.params.jobId === 'string' ? route.params.jobId : ''))
const promptCrumb = computed(() => {
  const match = route.path.match(/^\/prompts\/([^/]+)\/([^/]+)$/)
  if (!match) return ''
  return `${decodeURIComponent(match[1])}/${decodeURIComponent(match[2])}`
})

onMounted(async () => {
  if (userStore.loaded) return
  try {
    await userStore.load()
  } catch (error) {
    const e = toKeelError(error)
    if (e.code === 'AUTH_UNAUTHENTICATED' || e.code === 'AUTH_TOKEN_EXPIRED') return
    ElMessage.error(e.message || '暂时没有拿到当前用户，请稍后再试')
  }
})

async function logout() {
  await userStore.logout()
  router.push('/login')
}

function toLogin() {
  clearPreviewEntered()
  router.push('/login')
}

const roleLabel = computed(() => {
  if (userStore.user?.mode === 'PREVIEW') return '只读'
  if (userStore.user?.platformRole === 'ADMIN') return '平台管理员'
  return userStore.user?.platformRole ?? ''
})

watch(() => envStore.env, (env) => {
  approvalsStore.refresh(env).catch(() => undefined)
}, { immediate: true })
</script>

<template>
  <div class="app" :class="{ preview: userStore.readOnly }">
    <aside class="side">
      <div class="logo">
        <svg viewBox="0 0 24 24" fill="none">
          <path d="M2 9h20l-2 5c-4 3-12 3-16 0z" stroke="#ff7a45" stroke-width="1.8" />
          <path d="M10 16h4l-.8 6h-2.4z" fill="#ff7a45" />
        </svg>
        <div><b>Keel</b><small>agent platform</small></div>
      </div>
      <nav class="nav">
        <template v-for="[group, items] in groups" :key="group">
          <div class="grp">{{ group }}</div>
          <RouterLink
            v-for="item in items"
            :key="item.path"
            :to="item.path"
            :class="{ 'router-link-active': isNavActive(item.path, route.path) }"
          >
            <i>{{ item.icon }}</i>{{ item.title }}
            <span v-if="item.badge" class="nt">{{ item.badge }}</span>
            <span v-if="item.path === '/approvals' && approvalsStore.pending" class="cnt">{{ approvalsStore.pending }}</span>
          </RouterLink>
        </template>
      </nav>
      <div v-if="userStore.user" class="me">
        <template v-if="userStore.user.mode === 'PREVIEW'">
          <b>预览访客</b> · 只读<br /><a @click="toLogin">登录</a>
        </template>
        <template v-else>
          <b>{{ userStore.user.displayName }}</b> · {{ roleLabel }}<br />{{ userStore.user.org }} · <a @click="logout">退出</a>
        </template>
      </div>
    </aside>
    <div class="body">
      <header class="top">
        <div class="crumb">
          Keel 控制台 /
          <template v-if="traceId">
            <RouterLink to="/traces">链路追踪</RouterLink> / <b class="mono">{{ traceId }}</b>
          </template>
          <template v-else-if="jobCrumb">
            <RouterLink to="/jobs">研发任务</RouterLink> / <b class="mono">{{ jobCrumb }}</b>
          </template>
          <template v-else-if="promptCrumb">
            <RouterLink to="/prompts">提示词</RouterLink> / <b class="mono">{{ promptCrumb }}</b>
          </template>
          <b v-else>{{ title }}</b>
        </div>
        <div class="sp" />
        <button class="search" type="button" @click="palette = true"><span>⌕</span>搜索智能体、工具、页面<kbd>⌘K</kbd></button>
        <div class="envsel">
          <button
            v-for="option in ENV_OPTIONS"
            :key="option.value"
            :class="{ on: envStore.env === option.value }"
            @click="envStore.env = option.value"
          >
            {{ option.label }}
          </button>
        </div>
      </header>
      <div v-if="userStore.user?.mode === 'PREVIEW'" class="pvbar">
        <span>⚠</span>
        <span><b>预览模式 · 只读</b>，所有写操作已禁用</span>
        <button class="btn sm" type="button" @click="toLogin">登录</button>
      </div>
      <main class="main">
        <RouterView />
      </main>
    </div>
    <AgentDrawer />
    <CommandPalette v-model="palette" />
  </div>
</template>

<style scoped>
.app { display: flex; height: 100vh; }
.side {
  width: 208px;
  flex-shrink: 0;
  background: var(--panel);
  border-right: 1px solid var(--line);
  padding: 16px 10px;
  display: flex;
  flex-direction: column;
}
.logo { display: flex; align-items: center; gap: 10px; padding: 2px 8px 16px; }
.logo svg { width: 26px; height: 26px; }
.logo b { color: var(--white); font-size: 16px; display: block; line-height: 1.1; }
.logo small { color: var(--mute); font-family: var(--mono); font-size: 10px; }
.nav { flex: 1; overflow-y: auto; }
.grp { color: var(--mute); font-size: 11px; padding: 14px 10px 6px; letter-spacing: 1px; }
.nav a {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  color: var(--text);
  text-decoration: none;
  font-size: 13px;
}
.nav a i { font-style: normal; width: 16px; text-align: center; color: var(--mute); }
.nav a:hover { background: var(--panel2); }
.nav a.router-link-active { background: var(--panel3); color: var(--white); }
.nav a.router-link-active i { color: var(--acc); }
.cnt { margin-left: auto; background: var(--acc); color: #fff; border-radius: 8px; padding: 0 6px; font-size: 11px; }
.nt { margin-left: auto; font-size: 9.5px; border-radius: 3px; padding: 0 4px; font-weight: 700; background: var(--acc); color: #1a0d05; }
.me { color: var(--mute); font-size: 12px; padding: 12px 10px 0; border-top: 1px solid var(--line); line-height: 1.6; }
.me b { color: var(--white); }
.me a { color: var(--soft); cursor: pointer; }
.body { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.top {
  height: 52px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
}
.crumb { color: var(--mute); font-size: 13px; }
.crumb a { color: inherit; text-decoration: none; }
.crumb b { color: var(--white); font-weight: 500; }
.crumb .mono { font-family: var(--mono); }
.sp { flex: 1; }
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 5px 10px;
  color: var(--mute);
  font-size: 12.5px;
  width: 260px;
  cursor: pointer;
  margin-right: 12px;
  font-family: inherit;
}
.search kbd { margin-left: auto; font-family: var(--mono); font-size: 10.5px; border: 1px solid var(--line2); border-radius: 4px; padding: 0 5px; }
.envsel { display: flex; border: 1px solid var(--line2); border-radius: 6px; overflow: hidden; }
.envsel button {
  background: transparent;
  border: 0;
  color: var(--mute);
  padding: 5px 12px;
  font-size: 12px;
  cursor: pointer;
}
.envsel button.on { background: var(--panel3); color: var(--white); }
.main { flex: 1; overflow: auto; padding: 20px; }
</style>
