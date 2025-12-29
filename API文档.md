# REST API 接口文档

## 基础信息

- **Base URL**: `http://localhost:8080`
- **API版本**: v1
- **数据格式**: JSON
- **字符编码**: UTF-8

## 认证方式

大部分接口需要在请求头中携带JWT Token进行认证：

```
Authorization: Bearer {token}
```

登录接口不需要认证，注册相关接口不需要认证。

---

## 统一响应格式

所有接口都使用统一的响应格式：

```json
{
  "code": 200,
  "message": null,
  "data": {}
}
```

### 响应字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| code | Integer | 状态码，200表示成功，其他值表示失败 |
| message | String | 错误消息，成功时为null |
| data | Object/String | 响应数据，类型根据接口而定 |

---

## 错误码说明

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 1 | 用户已存在 |
| 2 | 邮箱已存在 |
| 3 | 邮箱不为系统支持的邮箱 |
| 4 | 验证码不存在或失效 |
| 5 | 用户不存在 |
| 6 | 新旧密码相同 |
| 400 | 参数错误 |
| 401 | 认证失败（未登录或Token过期） |
| 403 | 权限不足 |
| 404 | 请求路径不存在 |
| 405 | 请求方法不支持 |
| 500 | 系统内部错误 |

---

## 接口列表

### 1. 用户登录

**接口地址**: `POST /auth/login`

**接口说明**: 用户登录，返回JWT Token

**认证要求**: 无需认证

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| username | String | 是 | 用户名，2-20个字符 | "admin" |
| password | String | 是 | 密码，6-20个字符 | "123456" |

**请求示例**:

```json
{
  "username": "admin",
  "password": "123456"
}
```

**响应示例**:

成功：
```json
{
  "code": 200,
  "message": null,
  "data": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

失败：
```json
{
  "code": 401,
  "message": "用户名或密码错误",
  "data": null
}
```

---

### 2. 发送注册验证码

**接口地址**: `GET /auth/sendMailToRegister`

**接口说明**: 向指定邮箱发送注册验证码邮件，验证码5分钟内有效

**认证要求**: 无需认证

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| mailType | String | 是 | 邮箱类型，支持：qq、netease | "qq" |
| mailTo | String | 是 | 接收邮件的邮箱地址，必须是有效的邮箱格式 | "test@qq.com" |

**请求示例**:

```
GET /auth/sendMailToRegister?mailType=qq&mailTo=test@qq.com
```

**响应示例**:

成功：
```json
{
  "code": 200,
  "message": null,
  "data": "验证码发送成功"
}
```

失败：
```json
{
  "code": 3,
  "message": "不是系统支持的邮箱",
  "data": null
}
```

**注意事项**:
- 支持的邮箱类型：`qq`（QQ邮箱）、`netease`（网易邮箱，包括163.com和126.com）
- 验证码有效期：5分钟
- 同一个邮箱短时间内多次请求可能受到限制

---

### 3. 用户注册

**接口地址**: `POST /auth/register`

**接口说明**: 新用户注册

**认证要求**: 无需认证

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| username | String | 是 | 用户名，2-20个字符，只能包含字母、数字和下划线 | "testuser" |
| password | String | 是 | 密码，6-20个字符 | "123456" |
| email | String | 是 | 邮箱地址，必须是有效的邮箱格式 | "test@qq.com" |
| verifyCode | String | 是 | 邮箱验证码，4-10个字符 | "123456" |

**请求示例**:

```json
{
  "username": "testuser",
  "password": "123456",
  "email": "test@qq.com",
  "verifyCode": "123456"
}
```

**响应示例**:

成功：
```json
{
  "code": 200,
  "message": null,
  "data": {
    "username": "testuser",
    "password": "123456",
    "email": "test@qq.com",
    "verifyCode": "123456"
  }
}
```

失败（用户已存在）：
```json
{
  "code": 1,
  "message": "用户已存在",
  "data": null
}
```

失败（邮箱已存在）：
```json
{
  "code": 2,
  "message": "邮箱已存在",
  "data": null
}
```

失败（验证码错误）：
```json
{
  "code": 4,
  "message": "验证码过期或不存在",
  "data": null
}
```

---

### 4. 发送重置密码验证码

**接口地址**: `GET /account/sendMailToResetPwd`

**接口说明**: 向用户注册邮箱发送重置密码验证码邮件，验证码5分钟内有效

**认证要求**: 无需认证

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| username | String | 是 | 用户名 | "admin" |

**请求示例**:

```
GET /account/sendMailToResetPwd?username=admin
```

**响应示例**:

成功：
```json
{
  "code": 200,
  "message": null,
  "data": "验证码发送成功"
}
```

失败（用户不存在）：
```json
{
  "code": 5,
  "message": "用户不存在",
  "data": null
}
```

失败（邮箱不支持）：
```json
{
  "code": 3,
  "message": "邮箱不为系统支持的邮箱",
  "data": null
}
```

---

### 5. 重置密码

**接口地址**: `GET /account/resetPwd`

**接口说明**: 通过验证码重置用户密码

**认证要求**: 无需认证

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| verifyCode | String | 是 | 验证码 | "123456" |
| newPwd | String | 是 | 新密码，6-20个字符 | "newpass123" |

**请求示例**:

```
GET /account/resetPwd?verifyCode=123456&newPwd=newpass123
```

**响应示例**:

成功：
```json
{
  "code": 200,
  "message": null,
  "data": "密码重置成功"
}
```

失败（验证码错误）：
```json
{
  "code": 4,
  "message": "验证码不存在或失效",
  "data": null
}
```

失败（用户不存在）：
```json
{
  "code": 5,
  "message": "用户不存在",
  "data": null
}
```

失败（新旧密码相同）：
```json
{
  "code": 6,
  "message": "新旧密码相同",
  "data": null
}
```

---

### 6. 用户登出

**接口地址**: `GET /account/logout`

**接口说明**: 用户登出，清除登录状态

**认证要求**: 需要认证（Bearer Token）

**请求头**:

```
Authorization: Bearer {token}
```

**请求参数**: 无

**请求示例**:

```
GET /account/logout
Headers: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**响应示例**:

成功：
```json
{
  "code": 200,
  "message": null,
  "data": "登出成功"
}
```

失败（未登录）：
```json
{
  "code": 401,
  "message": "未登录",
  "data": null
}
```

---

## 前端开发注意事项

### 1. Token存储和使用

- 登录成功后，将返回的Token存储到本地（localStorage或sessionStorage）
- 后续所有需要认证的接口，都要在请求头中携带Token
- Token格式：`Bearer {token}`

### 2. Token过期处理

- Token过期时间为5分钟（可在后端配置中修改）
- 如果接口返回401错误码，说明Token已过期或无效，需要重新登录

### 3. 注册流程

1. 用户输入邮箱 → 调用 `/auth/sendMailToRegister` 发送验证码
2. 用户输入验证码和其他注册信息 → 调用 `/auth/register` 完成注册
3. 注册成功后，可以调用登录接口进行登录

### 4. 重置密码流程

1. 用户输入用户名 → 调用 `/account/sendMailToResetPwd` 发送验证码到注册邮箱
2. 用户输入验证码和新密码 → 调用 `/account/resetPwd` 完成密码重置

### 5. 错误处理

- 统一检查响应中的 `code` 字段
- `code === 200` 表示成功
- 其他 `code` 值表示失败，错误信息在 `message` 字段中

### 6. 参数校验

- 前端也应该进行参数校验（如用户名长度、密码长度等）
- 即使前端校验通过，后端也会再次校验
- 后端校验失败会返回 `code: 400`，错误信息在 `message` 中

---

## 前端请求示例（JavaScript/Axios）

### 登录示例

```javascript
// 登录
const login = async (username, password) => {
  try {
    const response = await axios.post('http://localhost:8080/auth/login', {
      username: username,
      password: password
    });
    
    if (response.data.code === 200) {
      // 登录成功，保存Token
      localStorage.setItem('token', response.data.data);
      return { success: true, token: response.data.data };
    } else {
      // 登录失败
      return { success: false, message: response.data.message };
    }
  } catch (error) {
    return { success: false, message: '网络错误' };
  }
};
```

### 带Token的请求示例

```javascript
// 登出
const logout = async () => {
  try {
    const token = localStorage.getItem('token');
    const response = await axios.get('http://localhost:8080/account/logout', {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    });
    
    if (response.data.code === 200) {
      // 登出成功，清除Token
      localStorage.removeItem('token');
      return { success: true };
    } else {
      return { success: false, message: response.data.message };
    }
  } catch (error) {
    return { success: false, message: '网络错误' };
  }
};
```

### Axios拦截器配置示例

```javascript
// 配置axios拦截器，自动添加Token
axios.interceptors.request.use(config => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
}, error => {
  return Promise.reject(error);
});

// 响应拦截器，统一处理401错误
axios.interceptors.response.use(response => {
  return response;
}, error => {
  if (error.response && error.response.status === 401) {
    // Token过期，清除并跳转到登录页
    localStorage.removeItem('token');
    // 跳转到登录页
    window.location.href = '/login';
  }
  return Promise.reject(error);
});
```

---

## 接口测试

可以使用Postman、Apifox等工具进行接口测试，也可以访问Swagger文档（如果已配置）：

```
http://localhost:8080/swagger-ui.html
```

---

## 分析任务接口

### 7. 创建分析任务

**接口地址**: `POST /api/analysis/task`

**接口说明**: 为指定视频创建分析任务

**认证要求**: 需要认证（Bearer Token）

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 | 示例 |
|--------|------|------|------|------|
| videoId | String | 是 | 视频ID | "abc123" |
| taskType | String | 否 | 任务类型，默认FULL_ANALYSIS | "FULL_ANALYSIS" |

**任务类型说明**:
- `FULL_ANALYSIS`: 完整分析（视频+音频+文本）
- `VIDEO_ONLY`: 仅视频分析
- `AUDIO_ONLY`: 仅音频分析
- `TEXT_ONLY`: 仅文本分析

**请求示例**:

```json
{
  "videoId": "abc123",
  "taskType": "FULL_ANALYSIS"
}
```

**响应示例**:

```json
{
  "code": 200,
  "message": "任务创建成功",
  "data": {
    "id": "task123",
    "videoId": "abc123",
    "videoTitle": "测试视频",
    "taskType": "FULL_ANALYSIS",
    "status": "PENDING",
    "progress": 0,
    "gmtCreated": "2024-01-15T10:30:00"
  }
}
```

---

### 8. 获取任务详情

**接口地址**: `GET /api/analysis/task/{taskId}`

**接口说明**: 根据任务ID获取任务详细信息

**认证要求**: 需要认证（Bearer Token）

**路径参数**:

| 参数名 | 类型 | 说明 |
|--------|------|------|
| taskId | String | 任务ID |

**响应示例**:

```json
{
  "code": 200,
  "data": {
    "id": "task123",
    "videoId": "abc123",
    "videoTitle": "测试视频",
    "videoUrl": "http://minio.example.com/videos/test.mp4",
    "taskType": "FULL_ANALYSIS",
    "status": "COMPLETED",
    "progress": 100,
    "startedAt": "2024-01-15T10:30:00",
    "completedAt": "2024-01-15T10:31:00",
    "hasResult": true,
    "resultId": "result123"
  }
}
```

---

### 9. 获取任务列表

**接口地址**: `GET /api/analysis/task/list`

**接口说明**: 分页获取当前用户的分析任务列表

**认证要求**: 需要认证（Bearer Token）

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 | 默认值 |
|--------|------|------|------|--------|
| status | String | 否 | 状态筛选 | - |
| page | int | 否 | 页码 | 1 |
| size | int | 否 | 每页数量 | 10 |

**任务状态说明**:
- `PENDING`: 等待处理
- `PROCESSING`: 处理中
- `COMPLETED`: 已完成
- `FAILED`: 失败
- `CANCELLED`: 已取消

---

### 10. 取消任务

**接口地址**: `POST /api/analysis/task/{taskId}/cancel`

**接口说明**: 取消指定的分析任务（仅限等待中或处理中的任务）

**认证要求**: 需要认证（Bearer Token）

---

### 11. 重试任务

**接口地址**: `POST /api/analysis/task/{taskId}/retry`

**接口说明**: 重新执行失败或已取消的任务

**认证要求**: 需要认证（Bearer Token）

---

## 分析结果接口

### 12. 获取分析结果

**接口地址**: `GET /api/analysis/result/{resultId}`

**接口说明**: 根据结果ID获取分析结果详细信息

**认证要求**: 需要认证（Bearer Token）

**响应示例**:

```json
{
  "code": 200,
  "data": {
    "id": "result123",
    "taskId": "task123",
    "videoId": "abc123",
    "videoTitle": "测试视频",
    "riskScore": 0.35,
    "riskLevel": "MEDIUM",
    "riskLevelDesc": "中风险",
    "isUniversityRelated": true,
    "universityName": "清华大学",
    "universityConfidence": 0.92,
    "topicCategory": "校园生活",
    "topicKeywords": ["大学生", "校园", "青春"],
    "sentimentScore": 0.45,
    "sentimentLabel": "POSITIVE",
    "sentimentLabelDesc": "正面",
    "videoFeatures": {
      "sceneType": "教室",
      "faceCount": 3
    },
    "audioFeatures": {
      "speechRatio": 0.65
    },
    "transcription": "大家好，今天我来分享...",
    "spreadPotential": 0.72,
    "gmtCreated": "2024-01-15T10:31:00"
  }
}
```

---

### 13. 根据视频ID获取结果

**接口地址**: `GET /api/analysis/result/video/{videoId}`

**接口说明**: 获取指定视频的最新分析结果

**认证要求**: 需要认证（Bearer Token）

---

### 14. 获取分析结果列表

**接口地址**: `GET /api/analysis/result/list`

**接口说明**: 分页获取当前用户的分析结果列表

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 | 默认值 |
|--------|------|------|------|--------|
| riskLevel | String | 否 | 风险等级筛选(LOW/MEDIUM/HIGH) | - |
| page | int | 否 | 页码 | 1 |
| size | int | 否 | 每页数量 | 10 |

---

### 15. 获取分析统计数据

**接口地址**: `GET /api/analysis/result/stats`

**接口说明**: 获取当前用户的分析统计数据

**响应示例**:

```json
{
  "code": 200,
  "data": {
    "totalVideos": 20,
    "analyzedVideos": 15,
    "totalResults": 15,
    "avgRiskScore": 0.35,
    "highRiskCount": 2,
    "mediumRiskCount": 5,
    "lowRiskCount": 8
  }
}
```

---

### 16. 获取风险分布

**接口地址**: `GET /api/analysis/result/risk-distribution`

**接口说明**: 获取当前用户视频的风险等级分布

**响应示例**:

```json
{
  "code": 200,
  "data": {
    "HIGH": 2,
    "MEDIUM": 5,
    "LOW": 8
  }
}
```

---

## 算法服务接口

### 17. 算法结果回调

**接口地址**: `POST /api/algorithm/callback`

**接口说明**: 接收算法服务的分析结果（供算法服务调用）

**认证要求**: 无需认证（生产环境应添加API密钥验证）

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| taskId | String | 是 | 任务ID |
| status | String | 是 | 状态: completed/failed |
| errorMessage | String | 否 | 错误信息（失败时提供） |
| riskScore | BigDecimal | 否 | 风险评分(0-1) |
| riskLevel | String | 否 | 风险等级 |
| ... | ... | ... | 其他分析结果字段 |

---

### 18. 更新任务进度

**接口地址**: `POST /api/algorithm/progress`

**接口说明**: 算法服务更新任务处理进度

**请求参数**:

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| taskId | String | 是 | 任务ID |
| progress | Integer | 是 | 进度(0-100) |
| message | String | 否 | 进度消息 |

---

## WebSocket 接口

### 19. 任务进度推送

**连接地址**: `ws://localhost:8080/ws/task-progress/{userId}`

**接口说明**: 建立WebSocket连接，接收任务进度实时推送

**消息格式**:

```json
{
  "type": "task_progress",
  "message": "任务进度更新",
  "timestamp": 1705294200000,
  "data": {
    "taskId": "task123",
    "status": "PROCESSING",
    "progress": 40,
    "message": "视频特征提取"
  }
}
```

**消息类型**:
- `connected`: 连接成功
- `task_progress`: 任务进度更新
- `task_completed`: 任务完成
- `task_failed`: 任务失败

**心跳**: 发送 `ping`，服务器响应 `pong`

---

## 更新日志

- 2024-xx-xx: 初始版本，包含基础的用户认证相关接口
- 2024-xx-xx: 新增分析任务管理、分析结果查询、算法回调、WebSocket通知接口

