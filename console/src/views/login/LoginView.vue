<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAuthOptions, getCaptcha, loginWithPassword, loginWithSms, sendLoginSms } from '@/api/auth'
import { KeelApiError, toKeelError } from '@/api/http'
import { clearPreviewEntered, markPreviewEntered, safeRedirect } from '@/router/redirect'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const tab = ref<'password' | 'sms'>('password')
const account = ref('')
const password = ref('')
const captcha = ref('')
const challengeId = ref('')
const captchaImage = ref('')
const phone = ref('')
const code = ref('')
const error = ref('')
const busy = ref(false)
const previewEnabled = ref(false)
const smsLeft = ref(0)
let smsTimer: ReturnType<typeof setInterval> | undefined

const nextPath = computed(() => safeRedirect(route.query.redirect))

onMounted(async () => {
  try {
    const options = await getAuthOptions()
    previewEnabled.value = options.previewEnabled
  } catch (cause) {
    error.value = toKeelError(cause).message
  }
})

onUnmounted(() => {
  if (smsTimer) clearInterval(smsTimer)
})

function show(cause: unknown) {
  const keel = toKeelError(cause)
  error.value = keel.message
  const image = keel.details?.captchaImage
  const id = keel.details?.challengeId
  if (typeof image === 'string' && typeof id === 'string') {
    captchaImage.value = image
    challengeId.value = id
    captcha.value = ''
  }
  return keel
}

async function refreshCaptcha() {
  try {
    const next = await getCaptcha()
    captchaImage.value = next.captchaImage
    challengeId.value = next.challengeId
    captcha.value = ''
  } catch (cause) {
    error.value = toKeelError(cause).message
  }
}

async function submitPassword() {
  error.value = ''
  if (!account.value.trim() || !password.value) {
    error.value = '请输入账号和密码'
    return
  }
  if (captchaImage.value && !captcha.value.trim()) {
    error.value = '请填写图形验证码。看不清可以点图片换一张'
    return
  }
  busy.value = true
  try {
    clearPreviewEntered()
    const user = await loginWithPassword({
      account: account.value.trim(),
      password: password.value,
      captcha: captcha.value.trim() || undefined,
      challengeId: challengeId.value || undefined,
    })
    userStore.adopt(user)
    await router.push(nextPath.value)
  } catch (cause) {
    show(cause)
    if (toKeelError(cause) instanceof KeelApiError && toKeelError(cause).code === 'AUTH_CAPTCHA_REQUIRED' && !captchaImage.value) {
      await refreshCaptcha()
    }
  } finally {
    busy.value = false
  }
}

async function submitSms() {
  error.value = ''
  if (!/^1\d{10}$/.test(phone.value.trim())) {
    error.value = '请输入 11 位中国大陆手机号'
    return
  }
  if (!/^\d{4,6}$/.test(code.value.trim())) {
    error.value = '请输入短信验证码'
    return
  }
  busy.value = true
  try {
    clearPreviewEntered()
    const user = await loginWithSms({ phone: phone.value.trim(), code: code.value.trim() })
    userStore.adopt(user)
    await router.push(nextPath.value)
  } catch (cause) {
    show(cause)
  } finally {
    busy.value = false
  }
}

async function sendCode() {
  error.value = ''
  if (!/^1\d{10}$/.test(phone.value.trim())) {
    error.value = '请输入 11 位中国大陆手机号'
    return
  }
  try {
    await sendLoginSms(phone.value.trim())
    error.value = ''
    smsLeft.value = 60
    smsTimer = setInterval(() => {
      smsLeft.value -= 1
      if (smsLeft.value <= 0 && smsTimer) clearInterval(smsTimer)
    }, 1000)
  } catch (cause) {
    show(cause)
  }
}

async function enterPreview() {
  markPreviewEntered()
  try {
    await userStore.load()
  } catch (cause) {
    error.value = toKeelError(cause).message
    return
  }
  await router.push(nextPath.value)
}
</script>

<template>
  <div class="login-page">
    <div class="lwrap">
      <section class="lhero" aria-label="Keel 智能体底座">
        <div>
          <div class="lh-eye">KEEL · AGENT PLATFORM</div>
          <h1 class="lh-title">一条<em>龙骨</em>，撑起所有智能体</h1>
          <p class="lh-sub">智能体只写业务逻辑。入口、模型、追踪、审计、审批、发布门禁由底座统一提供，十个智能体共用一套。</p>
        </div>
        <div>
          <div class="lh-tag">智能体层 <b>业务 5 · 研发 5 · Python / Java / Dify</b></div>
          <div class="lh-agents">
            <span style="--c:#2ec4b6"><i />careermate</span>
            <span style="--c:#5b9cf6"><i />askdb</span>
            <span style="--c:#34c38f"><i />offshore-wind</span>
            <span style="--c:#b48cf2"><i />cs-bot</span>
            <span style="--c:#ff7a45"><i />ops-copilot</span>
            <span style="--c:#f1b44c"><i />prd-agent</span>
            <span style="--c:#e36fae"><i />code-review</span>
            <span style="--c:#6fd3e3"><i />test-gen</span>
            <span style="--c:#f46a6a"><i />ci-doctor</span>
            <span style="--c:#ffb08f"><i />dev-copilot</span>
          </div>
        </div>
        <div class="lh-base">
          <div class="lh-cols">
            <div class="lh-col"><h5>接入<small>INGRESS</small></h5>
              <div class="lh-cap"><u>C01</u><b>入口网关</b><small>鉴权 · 配额 · SSE</small></div>
              <div class="lh-cap"><u>C02</u><b>模型网关</b><small>虚拟 Key · 人民币日预算</small></div>
              <div class="lh-cap"><u>C03</u><b>知识服务</b><small>rag-forge 统一检索</small></div>
            </div>
            <div class="lh-col"><h5>观测<small>OBSERVE</small></h5>
              <div class="lh-cap"><u>C04</u><b>链路追踪</b><small>跨智能体一条 trace</small></div>
              <div class="lh-cap"><u>C07</u><b>质量监控</b><small>评分 · 点踩率 · 成本</small></div>
              <div class="lh-cap"><u>C10</u><b>注册中心</b><small>自检 · 心跳 · 对账</small></div>
            </div>
            <div class="lh-col"><h5>治理<small>GOVERN</small></h5>
              <div class="lh-cap"><u>C05</u><b>审计中心</b><small>哈希链，只增不改</small></div>
              <div class="lh-cap"><u>C08</u><b>护栏策略</b><small>注入拦截 · 脱敏</small></div>
              <div class="lh-cap"><u>C09</u><b>工具注册</b><small>读写属性 · 风险等级</small></div>
              <div class="lh-cap"><u>C10</u><b>审批与人工介入</b><small>挂起 · 批准 · 恢复</small></div>
            </div>
            <div class="lh-col"><h5>发布<small>SHIP</small></h5>
              <div class="lh-cap"><u>C06</u><b>评测门禁</b><small>退步就不许发布</small></div>
              <div class="lh-cap"><u>C07</u><b>提示词</b><small>四环境标签 · 回滚</small></div>
              <div class="lh-cap"><u>CLI</u><b>脚手架</b><small>keel new · dev · release</small></div>
            </div>
          </div>
        </div>
        <div class="lh-stats">
          <div><b>10</b>智能体接入</div>
          <div><b>4</b>环境 dev → prod</div>
          <div><b>1</b>套契约 keel/v1</div>
          <div><b>0</b>把厂商 Key 在智能体里</div>
        </div>
      </section>
      <section class="lside">
        <div class="lbox">
          <div class="logo">
            <svg viewBox="0 0 24 24" fill="none" width="26" height="26">
              <path d="M2 9h20l-2 5c-4 3-12 3-16 0z" stroke="#ff7a45" stroke-width="1.8" />
              <path d="M10 16h4l-.8 6h-2.4z" fill="#ff7a45" />
            </svg>
            <div><b>Keel</b><small>agent platform</small></div>
          </div>
          <h2>登录 Keel 控制台</h2>
          <div class="lsub">账号由平台管理员开通，不提供注册</div>
          <div class="ltabs">
            <button type="button" :class="{ on: tab === 'password' }" @click="tab = 'password'">账号密码</button>
            <button type="button" :class="{ on: tab === 'sms' }" @click="tab = 'sms'">短信验证码</button>
          </div>
          <form v-if="tab === 'password'" @submit.prevent="submitPassword">
            <div class="field"><label for="lAcc">账号</label><input id="lAcc" v-model="account" class="inp" autocomplete="username" placeholder="用户名 / 手机号 / 邮箱"></div>
            <div class="field"><label for="lPwd">密码</label><input id="lPwd" v-model="password" class="inp" type="password" autocomplete="current-password" placeholder="密码"></div>
            <div v-if="captchaImage" class="field">
              <label for="lCap">图形验证码</label>
              <div class="lrow">
                <input id="lCap" v-model="captcha" class="inp" autocomplete="off" placeholder="输入右侧字符，不区分大小写">
                <button class="lcap" type="button" title="看不清，换一张" @click="refreshCaptcha"><img :src="captchaImage" alt="图形验证码"></button>
              </div>
            </div>
            <div class="lerr" role="alert">{{ error }}</div>
            <button class="btn pri" type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
          </form>
          <form v-else @submit.prevent="submitSms">
            <div class="field"><label for="lPhone">手机号</label><input id="lPhone" v-model="phone" class="inp" inputmode="numeric" maxlength="11" autocomplete="tel" placeholder="中国大陆手机号"></div>
            <div class="field">
              <label for="lCode">验证码</label>
              <div class="lrow">
                <input id="lCode" v-model="code" class="inp" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码">
                <button class="btn" type="button" :disabled="smsLeft > 0" @click="sendCode">{{ smsLeft > 0 ? `${smsLeft} 秒后重发` : '获取验证码' }}</button>
              </div>
            </div>
            <div class="lerr" role="alert">{{ error }}</div>
            <button class="btn pri" type="submit" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
          </form>
          <template v-if="previewEnabled">
            <div class="lor">或</div>
            <button class="btn pv" type="button" @click="enterPreview">以预览模式进入（只读）</button>
          </template>
          <div class="lfoot">没有账号或忘记密码，请联系平台管理员</div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.logo { display: flex; align-items: center; gap: 10px; padding: 0 0 12px; }
.logo b { color: var(--white); font-size: 16px; display: block; line-height: 1.1; }
.logo small { color: var(--mute); font-family: var(--mono); font-size: 10px; }
</style>
