// @ts-ignore
/* eslint-disable */
import request from "@/request";

/** 此处后端没有提供注释 GET /api/v1/devices */
export async function listMine(options?: { [key: string]: any }) {
  return request<Record<string, any>>("/api/v1/devices", {
    method: "GET",
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /api/v1/devices */
export async function register1(
  body: API.RegisterDeviceRequest,
  options?: { [key: string]: any }
) {
  return request<API.DeviceView>("/api/v1/devices", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    data: body,
    ...(options || {}),
  });
}
