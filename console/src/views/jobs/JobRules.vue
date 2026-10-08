<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getDevflowSettings, updateDevflowSettings, type DevflowSettings } from '@/api/devflow'
import { toKeelError } from '@/api/http'
import { useUserStore } from '@/stores/user'
import JobTabs from './JobTabs.vue'
import { TEMPLATES, toggleTemplate } from './jobs'

const user = useUserStore()
const form = ref<DevflowSettings | null>(null)
const loading = ref(false)
const busy = ref(false)
const admin = computed(() => user.user?.platformRole === 'ADMIN' && !user.readOnly)

async function load() {
  loading.value = true
  try {
    const current = await getDevflowSettings()
    form.value = { ...current, templates: [...current.templates] }
  } catch (error) {
    ElMessage.error(`加载规则失败：${toKeelError(error).message}`)
  } finally {
    loading.value = false
  }
}

function toggle(key: string) {
  if (!form.value || !admin.value) return
  form.value.templates = toggleTemplate(form.value.templates, key)
}

async function save() {
  if (!form.value) return
  busy.value = true
  try {
    const saved = await updateDevflowSettings(form.value)
    form.value = { ...saved, templates: [...saved.templates] }
    ElMessage.success('规则已保存，已写 config.change 审计')
  } catch (error) {
    ElMessage.error(toKeelError(error).message)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="vh">
      <h2>研发任务</h2>
      <span class="tag-new">新页面</span>
      <span class="sub">规则仅平台管理员可改，每次保存写 config.change 审计</span>
    </div>
    <JobTabs current="rules" />
    <div v-if="!admin" class="banner warn">当前身份只能查看。</div>

    <div v-if="form" class="row2">
      <div class="card">
        <h3>预算、轮次、并发</h3>
        <div class="grid2">
          <div class="field">
            <label>每任务预算上限（¥）</label>
            <input v-model.number="form.budgetCny" class="inp mono" type="number" :disabled="!admin">
            <div class="hint">估算值，试产后调整</div>
          </div>
          <div class="field">
            <label>修复轮次上限</label>
            <input v-model.number="form.maxFixRounds" class="inp mono" type="number" :disabled="!admin">
          </div>
          <div class="field">
            <label>新建智能体日预算上限（¥）</label>
            <input v-model.number="form.keyCapCny" class="inp mono" type="number" :disabled="!admin">
          </div>
          <div class="field">
            <label>每日新任务上限</label>
            <input v-model.number="form.dailyLimit" class="inp mono" type="number" :disabled="!admin">
            <div class="hint">升 Langfuse Core 前保持 3</div>
          </div>
          <div class="field">
            <label>批次并发上限</label>
            <input v-model.number="form.concurrency" class="inp mono" type="number" :disabled="!admin">
          </div>
        </div>
      </div>
      <div class="card">
        <h3>考题</h3>
        <div class="grid2">
          <div class="field">
            <label>隐藏考题比例（%）</label>
            <input v-model.number="form.holdoutPercent" class="inp mono" type="number" :disabled="!admin">
          </div>
          <div class="field">
            <label>人给种子用例最少条数</label>
            <input v-model.number="form.minSeed" class="inp mono" type="number" :disabled="!admin">
          </div>
        </div>
        <div class="field">
          <label>研发类任务</label>
          <input class="inp" value="强制人机协作 · 关口审批人为平台管理员（不可修改）" disabled>
        </div>
        <div class="field">
          <label>业务类开放的模板</label>
          <div class="chipsel">
            <button
              v-for="name in TEMPLATES"
              :key="name"
              type="button"
              :class="{ on: form.templates.includes(name) }"
              :disabled="!admin"
              @click="toggle(name)"
            >{{ name }}</button>
          </div>
        </div>
      </div>
    </div>
    <div v-if="admin" style="display: flex; justify-content: flex-end">
      <button v-write class="btn pri" type="button" :disabled="busy" @click="save">保存规则</button>
    </div>
  </div>
</template>
