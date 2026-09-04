import request from '@/request'
import { listMine } from '@/api/deviceController'
import { list } from '@/api/sessionController'

// 后端列表接口返回包裹对象（{"devices": [...]} / {"sessions": [...]}），这里统一解包
export async function listDevices() {
  const res = (await listMine()) as { devices?: API.DeviceView[] }
  return res.devices ?? []
}

export async function deleteDevice(id: number) {
  return request<void>(`/api/v1/devices/${id}`, {
    method: 'DELETE',
  })
}

export async function listSessions() {
  const res = (await list()) as { sessions?: API.SessionListItemView[] }
  return res.sessions ?? []
}

export async function deleteSession(id: number) {
  return request<void>(`/api/v1/sessions/${id}`, {
    method: 'DELETE',
  })
}
