<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { list1, setStatus } from '@/api/adminUserController'
import { authState } from '@/stores/auth'
import { getApiErrorMessage } from '@/utils/apiError'

interface AdminUserRecord extends API.UserView {
  disabled?: boolean
  status?: string
  userStatus?: string
}

const users = ref<AdminUserRecord[]>([])
const loading = ref(false)
const updatingId = ref<number | null>(null)
const keyword = ref('')
const includeDisabled = ref(true)
const pagination = reactive({
  current: 1,
  pageSize: 20,
  total: 0,
})

const columns = [
  { title: '用户', key: 'user', width: 260 },
  { title: '角色', dataIndex: 'userRole', key: 'userRole', width: 120 },
  { title: '状态', key: 'status', width: 120 },
  { title: '简介', dataIndex: 'userProfile', key: 'userProfile' },
  { title: '操作', key: 'action', width: 180, fixed: 'right' as const },
]

const currentUserId = computed(() => authState.currentUser.value?.id)

function resolveDisabled(user: AdminUserRecord): boolean | null {
  if (typeof user.disabled === 'boolean') return user.disabled
  const status = (user.userStatus || user.status || '').toLowerCase()
  if (['disabled', 'inactive', 'deleted'].includes(status)) return true
  if (['enabled', 'active', 'normal'].includes(status)) return false
  return null
}

const fetchUsers = async () => {
  loading.value = true
  try {
    const response = (await list1({
      page: pagination.current,
      size: pagination.pageSize,
      keyword: keyword.value.trim() || undefined,
      includeDisabled: includeDisabled.value,
    })) as unknown as API.AdminUserPageView
    users.value = (response.records ?? []) as AdminUserRecord[]
    pagination.total = response.total ?? 0
    pagination.current = response.page ?? pagination.current
    pagination.pageSize = response.size ?? pagination.pageSize
  } catch (error) {
    message.error(getApiErrorMessage(error, '加载用户列表失败'))
  } finally {
    loading.value = false
  }
}

const search = () => {
  pagination.current = 1
  fetchUsers()
}

const toggleDisabledUsers = () => {
  pagination.current = 1
  fetchUsers()
}

const changePage = (page: number, pageSize: number) => {
  pagination.current = page
  pagination.pageSize = pageSize
  fetchUsers()
}

const updateStatus = async (user: AdminUserRecord, disabled: boolean) => {
  if (!user.id || user.id === currentUserId.value) return
  updatingId.value = user.id
  try {
    await setStatus({ id: user.id }, { disabled })
    message.success(disabled ? '用户已禁用，现有令牌已失效' : '用户已启用，需要重新登录')
    await fetchUsers()
  } catch (error) {
    message.error(getApiErrorMessage(error, disabled ? '禁用用户失败' : '启用用户失败'))
  } finally {
    updatingId.value = null
  }
}

onMounted(fetchUsers)
</script>

<template>
  <div class="users-page">
    <div class="page-heading">
      <div>
        <span class="eyebrow">ADMIN</span>
        <h1>用户管理</h1>
        <p>查询系统用户，并启用或逻辑禁用账号。</p>
      </div>
      <a-tag color="blue">共 {{ pagination.total }} 位用户</a-tag>
    </div>

    <div class="toolbar">
      <a-input-search
        v-model:value="keyword"
        class="search-input"
        allow-clear
        placeholder="搜索账号或昵称"
        enter-button="搜索"
        @search="search"
      />
      <label class="disabled-filter">
        <a-switch v-model:checked="includeDisabled" @change="toggleDisabledUsers" />
        包含已禁用用户
      </label>
    </div>

    <a-table
      :columns="columns"
      :data-source="users"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 900 }"
      row-key="id"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'user'">
          <div class="user-cell">
            <a-avatar :src="record.userAvatar">
              {{ (record.userName || record.userAccount || '?').slice(0, 1).toUpperCase() }}
            </a-avatar>
            <div>
              <strong>{{ record.userName || record.userAccount || '未命名用户' }}</strong>
              <span>@{{ record.userAccount || '-' }} · ID {{ record.id ?? '-' }}</span>
            </div>
          </div>
        </template>

        <template v-else-if="column.key === 'userRole'">
          <a-tag :color="record.userRole?.toLowerCase() === 'admin' ? 'blue' : 'default'">
            {{ record.userRole?.toLowerCase() === 'admin' ? '管理员' : '普通用户' }}
          </a-tag>
        </template>

        <template v-else-if="column.key === 'status'">
          <a-tag v-if="resolveDisabled(record) === true" color="error">已禁用</a-tag>
          <a-tag v-else-if="resolveDisabled(record) === false" color="success">正常</a-tag>
          <span v-else class="muted">以服务端为准</span>
        </template>

        <template v-else-if="column.key === 'userProfile'">
          <span>{{ record.userProfile || '暂无简介' }}</span>
        </template>

        <template v-else-if="column.key === 'action'">
          <span v-if="record.id === currentUserId" class="muted">当前账号不可操作</span>
          <a-space v-else>
            <a-popconfirm
              title="确定启用该用户吗？用户仍需重新登录。"
              ok-text="启用"
              cancel-text="取消"
              @confirm="updateStatus(record, false)"
            >
              <a-button
                type="link"
                size="small"
                :disabled="resolveDisabled(record) === false"
                :loading="updatingId === record.id"
              >
                启用
              </a-button>
            </a-popconfirm>
            <a-popconfirm
              title="确定禁用该用户吗？其所有登录令牌将立即失效。"
              ok-text="禁用"
              cancel-text="取消"
              ok-type="danger"
              @confirm="updateStatus(record, true)"
            >
              <a-button
                type="link"
                danger
                size="small"
                :disabled="resolveDisabled(record) === true"
                :loading="updatingId === record.id"
              >
                禁用
              </a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
      <template #emptyText>
        <a-empty description="没有找到符合条件的用户" />
      </template>
    </a-table>

    <div class="pagination-row">
      <a-pagination
        :current="pagination.current"
        :page-size="pagination.pageSize"
        :total="pagination.total"
        show-size-changer
        :show-total="(total: number) => `共 ${total} 条`"
        @change="changePage"
      />
    </div>
  </div>
</template>

<style scoped>
.users-page {
  padding: 28px;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 8px 32px rgb(15 35 62 / 6%);
}

.page-heading,
.toolbar,
.user-cell,
.pagination-row {
  display: flex;
  align-items: center;
}

.page-heading {
  justify-content: space-between;
  margin-bottom: 24px;
}

.eyebrow {
  color: #1677ff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.page-heading h1 {
  margin: 4px 0;
  font-size: 26px;
}

.page-heading p,
.muted,
.user-cell span {
  color: rgb(0 0 0 / 45%);
}

.toolbar {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  padding: 16px;
  background: #f7f9fc;
  border-radius: 10px;
}

.search-input {
  max-width: 420px;
}

.disabled-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.user-cell {
  gap: 10px;
}

.user-cell div {
  display: flex;
  flex-direction: column;
}

.user-cell span {
  font-size: 12px;
}

.pagination-row {
  justify-content: flex-end;
  margin-top: 20px;
}

@media (max-width: 700px) {
  .users-page {
    padding: 18px;
  }

  .toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .search-input {
    max-width: none;
  }
}
</style>
