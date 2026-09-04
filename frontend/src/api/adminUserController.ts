// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 分页查询用户 按用户 ID 升序分页查询用户，可使用 keyword 对账号或昵称进行模糊搜索。默认不返回已禁用用户；includeDisabled=true 时同时返回已禁用用户。 GET /api/v1/admin/users */
export async function list1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.list1Params,
  options?: { [key: string]: any }
) {
  return request<API.AdminUserPageView>("/api/v1/admin/users", {
    method: "GET",
    params: {
      // page has a default value: 1
      page: "1",
      // size has a default value: 20
      size: "20",

      ...params,
    },
    ...(options || {}),
  });
}

/** 禁用或启用用户 disabled=true 时禁用目标用户并使其全部访问令牌立即失效；disabled=false 时恢复用户，但历史令牌不会恢复，用户需要重新登录。管理员不能禁用或启用自己的账号。 PUT /api/v1/admin/users/${param0}/status */
export async function setStatus(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.setStatusParams,
  body: API.SetUserStatusRequest,
  options?: { [key: string]: any }
) {
  const { id: param0, ...queryParams } = params;
  return request<API.UserView>(`/api/v1/admin/users/${param0}/status`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    params: { ...queryParams },
    data: body,
    ...(options || {}),
  });
}
