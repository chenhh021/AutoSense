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

  type approvalParams = {
    sessionId: number;
    requestId: string;
  };

  type ApprovalView = {
    approvalId?: string;
    stepId?: string;
    prompt?: string;
    expiresAt?: string;
    operation?: string;
    parameters?: Record<string, any>;
  };

  type BaseResponseDeviceView = {
    code?: number;
    data?: DeviceView;
    message?: string;
  };

  type BaseResponseLoginResponse = {
    code?: number;
    data?: LoginResponse;
    message?: string;
  };

  type BaseResponseUserView = {
    code?: number;
    data?: UserView;
    message?: string;
  };

  type cancelParams = {
    sessionId: number;
    requestId: string;
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

  type Data = {
    eventId?: string;
    sequence?: number;
    requestId?: string;
    conversationId?: number;
    stepId?: string;
    stepType?:
      | "KNOWLEDGE_CONSULT"
      | "DEVICE_QUERY"
      | "FAULT_DIAGNOSIS"
      | "DEVICE_CONTROL";
    status?:
      | "CREATED"
      | "PLANNING"
      | "VALIDATING"
      | "RUNNING"
      | "WAITING_APPROVAL"
      | "WAITING_INPUT"
      | "RETRYING"
      | "WAITING_RESUME"
      | "COMPLETED"
      | "FAILED"
      | "REJECTED"
      | "CANCELLED";
    progress?: Progress;
    version?: number;
    payload?: Record<string, any>;
  };

  type deleteUsingDELETEParams = {
    sessionId: number;
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

  type getParams = {
    sessionId: number;
  };

  type list1Params = {
    page?: number;
    size?: number;
    keyword?: string;
    includeDisabled?: boolean;
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
    inputRequestId?: string;
    expectedVersion?: number;
  };

  type messagesParams = {
    sessionId: number;
  };

  type postMessageParams = {
    sessionId: number;
  };

  type Progress = {
    total?: number;
    completed?: number;
    skipped?: number;
    notExecuted?: number;
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

  type resumeParams = {
    sessionId: number;
    requestId: string;
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
    workflow?: WorkflowView;
  };

  type setStatusParams = {
    id: number;
  };

  type SetUserStatusRequest = {
    /** 目标状态：true 表示禁用并使全部令牌失效，false 表示启用 */
    disabled: boolean;
  };

  type SseEmitter = {
    timeout?: number;
  };

  type StepView = {
    stepId?: string;
    type?:
      | "KNOWLEDGE_CONSULT"
      | "DEVICE_QUERY"
      | "FAULT_DIAGNOSIS"
      | "DEVICE_CONTROL";
    status?:
      | "PENDING"
      | "RUNNING"
      | "WAITING_APPROVAL"
      | "RETRYING"
      | "COMPLETED"
      | "SKIPPED"
      | "FAILED"
      | "REJECTED"
      | "NOT_EXECUTED";
    result?: Record<string, any>;
    failureCode?: string;
    commandExecutionId?: string;
    resultCertainty?:
      | "NOT_SENT"
      | "IN_FLIGHT"
      | "SUCCEEDED"
      | "FAILED"
      | "UNKNOWN";
    retriesUsed?: number;
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

  type WorkflowApprovalRequest = {
    stepId: string;
    approvalId: string;
    approved: boolean;
    expectedVersion: number;
  };

  type WorkflowCancelRequest = {
    expectedVersion: number;
  };

  type WorkflowEvent = {
    type?: string;
    code?: string;
    message?: string;
    data?: Data;
  };

  type workflowParams = {
    sessionId: number;
    requestId: string;
  };

  type WorkflowResumeRequest = {
    expectedVersion: number;
  };

  type WorkflowView = {
    requestId?: string;
    conversationId?: number;
    status?:
      | "CREATED"
      | "PLANNING"
      | "VALIDATING"
      | "RUNNING"
      | "WAITING_APPROVAL"
      | "WAITING_INPUT"
      | "RETRYING"
      | "WAITING_RESUME"
      | "COMPLETED"
      | "FAILED"
      | "REJECTED"
      | "CANCELLED";
    currentStep?: number;
    progress?: Progress;
    version?: number;
    canResume?: boolean;
    steps?: StepView[];
    approval?: ApprovalView;
    failureCode?: string;
    inputRequestId?: string;
    prompt?: string;
  };
}
