// 设备目录：与后端 application.yaml 中 autosense.device-types 注册表保持一致
// 后端暂无目录查询接口，新增设备类型/型号时需同步更新此处
export interface DeviceModelOption {
  code: string
  label: string
}

export interface DeviceTypeOption {
  code: string
  label: string
  models: DeviceModelOption[]
}

export const DEVICE_TYPE_OPTIONS: DeviceTypeOption[] = [
  {
    code: 'smart_bulb',
    label: '智能灯泡',
    models: [
      { code: 'LA001', label: '单色灯泡' },
      { code: 'LB001', label: '彩光灯泡' },
    ],
  },
]
