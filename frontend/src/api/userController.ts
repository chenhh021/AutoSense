// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 用户登录 校验账号和密码并签发不透明 Bearer 访问令牌。账号不存在、密码错误或账号已禁用时，统一返回 401，避免泄露具体失败原因。 POST /api/v1/users/login */
export async function login(
  body: API.LoginRequest,
  options?: { [key: string]: any }
) {
  return request<API.LoginResponse>("/api/v1/users/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}

/** 用户注销 删除当前会话的访问令牌。注销成功后，该令牌立即失效，接口不返回响应体。 POST /api/v1/users/logout */
export async function logout(options?: { [key: string]: any }) {
  return request<any>("/api/v1/users/logout", {
    method: "POST",
    ...(options || {}),
  });
}

/** 获取当前用户 根据 Bearer 访问令牌查询当前登录用户，返回结果不包含密码。 GET /api/v1/users/me */
export async function me(options?: { [key: string]: any }) {
  return request<API.UserView>("/api/v1/users/me", {
    method: "GET",
    ...(options || {}),
  });
}

/** 注册用户 创建普通用户账号。账号须为 4~32 位字母、数字或下划线；密码须为 8~64 位且同时包含字母和数字；注册角色固定为 user。 POST /api/v1/users/register */
export async function register(
  body: API.RegisterRequest,
  options?: { [key: string]: any }
) {
  return request<API.UserView>("/api/v1/users/register", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
