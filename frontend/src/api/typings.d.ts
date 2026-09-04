declare namespace API {
  type AdminUserPageView = {
    /** 符合查询条件的用户总数 */
    total?: number;
    /** 当前页码 */
    page?: number;
    /** 当前每页记录数 */
    size?: number;
    /** 当前页的脱敏用户列表 */
    records?: UserView[];
  };

  type AfterSalesLocation = {
    name?: string;
    address?: string;
    phone?: string;
    distanceMeters?: number;
  };

  type ChatMessageView = {
    role?: string;
    content?: string;
    createdAt?: string;
  };

  type ConclusionDto = {
    type?: string;
    summary?: string;
    preDiagnostics?: Record<string, any>;
    postDiagnostics?: Record<string, any>;
    manualSteps?: string[];
    afterSales?: AfterSalesLocation[];
  };

  type CreateSessionRequest = {
    problem: string;
  };

  type DeviceView = {
    id?: number;
    name?: string;
    simulatorName?: string;
    sn?: string;
    deviceTypeCode?: string;
    deviceTypeId?: number;
    deviceModelCode?: string;
    deviceModelId?: number;
    supported?: boolean;
    online?: boolean;
  };

  type ErrorResponse = {
    /** 业务错误码 */
    code?: string;
    /** 便于阅读的错误原因 */
    message?: string;
    /** 关联的诊断会话 ID；用户账号接口通常为空 */
    sessionId?: number;
  };

  type getParams = {
    sessionId: number;
  };

  type list1Params = {
    /** 页码，从 1 开始；小于 1 时按 1 处理 */
    page?: number;
    /** 每页记录数，取值会被限制在 1~100 */
    size?: number;
    /** 账号或昵称模糊搜索关键字；不传或空白时查询全部 */
    keyword?: string;
    /** 是否包含已禁用用户 */
    includeDisabled?: boolean;
  };

  type listMessagesParams = {
    sessionId: number;
  };

  type LoginRequest = {
    /** 用户登录账号 */
    userAccount: string;
    /** 用户登录密码 */
    userPassword: string;
  };

  type LoginResponse = {
    /** 访问令牌，在需要认证的接口中通过 Authorization 请求头传递 */
    token?: string;
    /** 令牌类型，固定为 Bearer */
    tokenType?: string;
    /** 当前登录用户的脱敏信息 */
    user?: UserView;
  };

  type MessageRequest = {
    content?: string;
    confirmRepair?: boolean;
  };

  type postMessageParams = {
    sessionId: number;
  };

  type RegisterDeviceRequest = {
    sn: string;
    name: string;
  };

  type RegisterRequest = {
    /** 登录账号，须为 4~32 位字母、数字或下划线 */
    userAccount: string;
    /** 登录密码，须为 8~64 位且同时包含字母和数字 */
    userPassword: string;
    /** 确认密码，必须与登录密码一致 */
    confirmPassword: string;
  };

  type SessionListItemView = {
    sessionId?: number;
    status?: string;
    preview?: string;
    createdAt?: string;
    updatedAt?: string;
  };

  type SessionResponse = {
    sessionId?: number;
    status?: string;
    reply?: string;
    awaitingInput?: boolean;
    conclusion?: ConclusionDto;
  };

  type setStatusParams = {
    /** 需要禁用或启用的目标用户 ID */
    id: number;
  };

  type SetUserStatusRequest = {
    /** 目标状态：true 表示禁用并使全部令牌失效，false 表示启用 */
    disabled: boolean;
  };

  type SseEmitter = {
    timeout?: number;
  };

  type UserView = {
    /** 用户唯一标识 */
    id?: number;
    /** 用户登录账号 */
    userAccount?: string;
    /** 用户昵称 */
    userName?: string;
    /** 用户头像地址 */
    userAvatar?: string;
    /** 用户简介 */
    userProfile?: string;
    /** 用户角色 */
    userRole?: "user" | "admin";
  };
}
