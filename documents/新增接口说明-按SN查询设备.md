# 新增接口说明：通过设备 SN 查询设备

## 1. 接口概述

通过设备序列号（SN）查询设备是否可被发现。只有处于运行状态的设备会被视为存在；未知、已停止或已删除的设备统一按不存在处理。

| 项目 | 说明 |
| --- | --- |
| 请求方法 | `GET` |
| 请求路径 | `/api/v1/devices/by-sn/{sn}` |
| 默认地址 | `http://localhost:8080/api/v1/devices/by-sn/{sn}` |
| 请求体 | 无 |
| 响应格式 | `application/json` |

## 2. 路径参数

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sn` | string | 是 | 设备序列号，区分大小写，必须符合 `^[A-Z0-9]{4}[0-9]{9}$` |

SN 总长度为 13 个字符：

- 前 4 位：设备类型代号，只允许大写英文字母或数字。
- 后 9 位：只允许数字。
- 格式正确但类型代号未知时，不视为参数错误，返回 `exists: false`。

示例：

| SN | 是否符合格式 | 说明 |
| --- | --- | --- |
| `LITE123456789` | 是 | 智能灯泡 SN |
| `ZZZZ000000000` | 是 | 类型代号未知，但格式正确 |
| `lite123456789` | 否 | 包含小写字母 |
| `LITE12345678` | 否 | 长度不足 13 位 |
| `LITE12345678A` | 否 | 后 9 位包含非数字字符 |

## 3. 响应说明

### 3.1 查询到运行中的设备

HTTP 状态码：`200 OK`

响应在设备完整信息的同一层增加 `exists: true`，不使用 `device` 包装字段。除 `exists` 外，其余字段和值与 `GET /api/v1/devices/{id}` 返回的设备详情一致。

```json
{
  "exists": true,
  "device_type_code": "LITE",
  "device_model_code": "LA001",
  "id": 1,
  "device_type_id": 1,
  "device_model_id": 1,
  "sn": "LITE123456789",
  "name": "LA001-a3f9b2",
  "running_status": "running",
  "state": {
    "brightness": 50,
    "color_temperature": 4600
  },
  "created_at": "2026-09-03T10:00:00+08:00",
  "updated_at": "2026-09-03T10:00:00+08:00"
}
```

主要字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `exists` | boolean | 固定为 `true` |
| `id` | number | 设备 ID |
| `device_type_id` | number | 设备类型 ID |
| `device_model_id` | number | 设备型号 ID |
| `device_type_code` | string | 设备类型代号，例如 `LITE` |
| `device_model_code` | string | 设备型号，例如 `LA001`、`LB001` |
| `sn` | string | 设备序列号 |
| `name` | string | 设备名称 |
| `running_status` | string | 运行状态；此响应中固定为 `running` |
| `state` | object | 设备当前状态，字段随型号变化 |
| `created_at` | string | 创建时间，ISO 8601 格式 |
| `updated_at` | string | 最后更新时间，ISO 8601 格式 |

不同型号的 `state` 内容：

- `LA001`：`brightness`、`color_temperature`。
- `LB001`：`brightness`、`color`，其中 `color` 包含 `r`、`g`、`b`。

### 3.2 设备不可发现

HTTP 状态码：`200 OK`

以下情况返回完全相同的响应：

- SN 格式正确，但不存在对应设备。
- 对应设备已经停止。
- 对应设备已经删除。

响应体只包含 `exists`，不会返回或泄露其他设备字段：

```json
{
  "exists": false
}
```

已停止的设备重新启动后，再次查询会恢复为 `exists: true`。

### 3.3 SN 格式错误

HTTP 状态码：`400 Bad Request`

```json
{
  "code": "INVALID_REQUEST",
  "message": "serial number must contain a 4-character type code followed by 9 digits"
}
```

格式错误响应不包含 `exists` 或任何设备字段。

### 3.4 服务内部错误

HTTP 状态码：`500 Internal Server Error`

```json
{
  "code": "INTERNAL_ERROR",
  "message": "具体错误信息"
}
```

数据库连接等内部错误不会被转换为 `exists: false`。

## 4. 调用示例

### curl

```bash
curl -X GET "http://localhost:8080/api/v1/devices/by-sn/LITE123456789" \
  -H "Accept: application/json"
```

### Windows PowerShell

```powershell
$SN = "LITE123456789"
$Result = Invoke-RestMethod `
  -Method Get `
  -Uri "http://localhost:8080/api/v1/devices/by-sn/$SN"

if ($Result.exists) {
    Write-Host "设备存在，ID：$($Result.id)，型号：$($Result.device_model_code)"
} else {
    Write-Host "设备不存在或当前不可发现"
}
```

### JavaScript

```javascript
const sn = "LITE123456789";
const response = await fetch(
  `http://localhost:8080/api/v1/devices/by-sn/${encodeURIComponent(sn)}`
);
const result = await response.json();

if (!response.ok) {
  throw new Error(`${result.code}: ${result.message}`);
}

if (result.exists) {
  console.log("设备信息：", result);
} else {
  console.log("设备不存在或当前不可发现");
}
```

## 5. 调用注意事项

1. 调用方应先根据 HTTP 状态码判断请求是否成功，再读取 `exists`。
2. `exists: false` 只代表设备当前不可发现，不能用于区分未知、停止和删除三种状态。
3. 当 `exists: false` 时，不应访问 `id`、`state` 等设备字段，因为这些字段不会返回。
4. JSON 对象的字段顺序不属于接口契约，调用方不应依赖字段顺序。
5. SN 查询为精确匹配，调用前不要自动转换大小写或删除字符。

## 6. 相关接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/devices/{id}` | 按 ID 获取运行设备完整信息 |
| `POST` | `/api/v1/devices/{id}/start` | 启动设备，使其可被 SN 查询发现 |
| `POST` | `/api/v1/devices/{id}/stop` | 停止设备，使 SN 查询返回 `exists: false` |
| `DELETE` | `/api/v1/devices/{id}` | 删除设备，使 SN 查询返回 `exists: false` |

---

文档版本：`1.0`  
更新时间：`2026-09-04`
