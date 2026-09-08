<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { listMine, register1 as register } from '@/api/deviceController'

const devices = ref<API.DeviceView[]>([])
const loading = ref(false)
const searchName = ref('')

// 设备列表：支持按名称过滤，每页最多 20 个
const filteredDevices = computed(() => {
  const keyword = searchName.value.trim().toLowerCase()
  if (!keyword) return devices.value
  return devices.value.filter((device) => device.name?.toLowerCase().includes(keyword))
})

const columns = [
  { title: '设备名称', dataIndex: 'name', key: 'name' },
  { title: '类型', dataIndex: 'deviceTypeCode', key: 'deviceTypeCode' },
  { title: '型号', dataIndex: 'deviceModelCode', key: 'deviceModelCode' },
  { title: 'SN', dataIndex: 'sn', key: 'sn' },
  { title: '状态', dataIndex: 'online', key: 'online' },
]

const fetchDevices = async () => {
  loading.value = true
  try {
    devices.value = ((await listMine()) as { devices?: API.DeviceView[] }).devices ?? []
  } catch (e) {
    message.error('加载设备列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(fetchDevices)

// 新增设备弹窗
const addModalOpen = ref(false)
const addLoading = ref(false)
const addForm = reactive({
  sn: '',
  name: '',
})

const openAddModal = () => {
  addForm.sn = ''
  addForm.name = ''
  addModalOpen.value = true
}

const submitAdd = async () => {
  const sn = addForm.sn.trim()
  const name = addForm.name.trim()
  if (!sn || !name) {
    message.warning('请输入设备 SN 和名称')
    return
  }
  addLoading.value = true
  try {
    await register({ sn, name })
    message.success('添加设备成功')
    addModalOpen.value = false
    fetchDevices()
  } catch (e) {
    message.error('添加设备失败，请检查 SN 是否正确')
  } finally {
    addLoading.value = false
  }
}
</script>

<template>
  <div class="devices-page">
    <!-- 上方：搜索 + 添加设备 -->
    <div class="toolbar">
      <a-input-search
        v-model:value="searchName"
        placeholder="按设备名称搜索"
        class="search-input"
        allow-clear
      />
      <a-button type="primary" @click="openAddModal">添加设备</a-button>
    </div>

    <!-- 下方：设备列表 -->
    <a-table
      :columns="columns"
      :data-source="filteredDevices"
      :loading="loading"
      :pagination="{ pageSize: 20, showTotal: (total: number) => `共 ${total} 台设备` }"
      :locale="{ emptyText: '暂无设备，点击右上角「添加设备」创建' }"
      row-key="id"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'online'">
          <a-tag :color="record.online ? 'green' : 'default'">
            {{ record.online ? '在线' : '离线' }}
          </a-tag>
        </template>
      </template>
    </a-table>

    <!-- 添加设备弹窗 -->
    <a-modal
      v-model:open="addModalOpen"
      title="添加设备"
      ok-text="添加"
      cancel-text="取消"
      :confirm-loading="addLoading"
      @ok="submitAdd"
    >
      <a-form layout="vertical">
        <a-form-item label="设备 SN" required>
          <a-input v-model:value="addForm.sn" placeholder="请输入设备序列号（SN）" />
        </a-form-item>
        <a-form-item label="设备名称" required>
          <a-input v-model:value="addForm.name" placeholder="例如：客厅路由器" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.devices-page {
  padding: 24px;
  background: #fff;
  border-radius: 8px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.search-input {
  max-width: 320px;
}
</style>
