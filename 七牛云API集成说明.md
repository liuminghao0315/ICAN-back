# 七牛云API集成说明

## 概述

本项目已成功集成七牛云的内容审核API，替换了原有的模拟算法。现在系统使用七牛云的视频审核、音频审核和文本审核API进行真实的内容审核。

## 主要变化

### 1. 新增文件

```
├── qiniu_config.py              # 七牛云配置模块
├── qiniu_video_processor.py     # 七牛云视频审核模块
├── qiniu_audio_processor.py     # 七牛云音频审核模块
├── qiniu_text_processor.py      # 七牛云文本审核模块
├── qiniu_algorithm_simulator.py # 七牛云算法模拟器（主程序）
├── .env.example                 # 环境变量示例文件
├── start_qiniu_algorithm.bat    # Windows启动脚本
└── requirements.txt             # Python依赖（已更新）
```

### 2. 架构改进

- **保持原有异步架构**：继续使用RabbitMQ进行Java-Python通信
- **无缝替换**：新模块保持与原有模块相同的接口，无需修改Java端代码
- **错误处理**：完善的错误处理和回退机制
- **配置管理**：支持环境变量配置，便于部署

## 配置步骤

### 步骤1：安装Python依赖

```bash
pip install -r requirements.txt
```

### 步骤2：配置七牛云密钥

1. 复制示例配置文件：
   ```bash
   copy .env.example .env
   ```

2. 编辑 `.env` 文件，填入七牛云的AccessKey和SecretKey：
   ```
   QINIU_ACCESS_KEY=你的AccessKey
   QINIU_SECRET_KEY=你的SecretKey
   ```

### 步骤3：启动算法模拟器

**Windows用户**：
```bash
start_qiniu_algorithm.bat
```

**Linux/Mac用户**：
```bash
python qiniu_algorithm_simulator.py
```

## 七牛云API配置说明

### 审核场景配置

在 `qiniu_config.py` 中可以配置审核场景：

```python
# 视频审核场景
'video': {
    'scenes': ['pulp', 'terror', 'politician', 'ads'],  # 色情、暴恐、政治人物、广告
    'async': True,  # 异步审核
    'callback': CALLBACK_URL,  # 回调URL
}

# 音频审核场景
'audio': {
    'scenes': ['antispam'],  # 反垃圾审核
    'async': True,
}

# 文本审核场景
'text': {
    'scenes': ['antispam'],  # 反垃圾审核
    'async': True,
}
```

### 回调URL配置

七牛云支持异步审核，审核完成后会回调到指定的URL。默认配置为：
```
http://localhost:8080/api/algorithm/callback
```

实际部署时需要替换为公网可访问的URL。

## 接口兼容性

### 保持兼容的接口

新模块保持了与原有模块完全相同的接口：

1. **视频处理**：
   ```python
   # 原有接口
   from 视频处理 import process_video
   
   # 新接口（兼容）
   from qiniu_video_processor import process_video
   ```

2. **音频处理**：
   ```python
   # 原有接口
   from 音频处理 import extract_audio_features, convert_audio_to_text
   
   # 新接口（兼容）
   from qiniu_audio_processor import extract_audio_features
   from qiniu_text_processor import convert_audio_to_text
   ```

### 结果格式

新模块返回的结果格式与原有模块基本一致，增加了七牛云API特有的字段：

```json
{
  "riskScore": 0.25,           // 风险评分 (0-1)
  "riskLevel": "LOW",          // 风险等级
  "hasViolation": false,        // 是否违规
  "processingTime": 2.5,       // 处理时间
  "apiJobId": "job_123456",    // 七牛云任务ID
  "apiStatus": "success",      // API状态
  "source": "qiniu_video_censor" // 数据来源
}
```

## 错误处理机制

### 1. 配置验证
- 启动时验证七牛云配置
- 配置无效时使用回退结果

### 2. API调用失败处理
- 网络超时：自动重试机制
- API错误：返回错误信息，使用回退结果
- 连接中断：自动重连

### 3. 回退结果
当七牛云API调用失败时，系统会返回一个安全的回退结果：
- 风险评分：0.0
- 风险等级：LOW
- 违规状态：false

## 测试方法

### 1. 单元测试
每个模块都包含测试代码：
```bash
# 测试视频审核
python qiniu_video_processor.py

# 测试音频审核
python qiniu_audio_processor.py

# 测试文本审核
python qiniu_text_processor.py
```

### 2. 集成测试
1. 确保RabbitMQ服务运行
2. 启动七牛云算法模拟器
3. 通过Java端发送测试任务
4. 查看Python端日志和返回结果

## 部署注意事项

### 1. 网络要求
- 需要能够访问七牛云API端点
- 七牛云需要能够回调到您的服务器

### 2. 性能考虑
- 七牛云API有调用频率限制
- 建议配置适当的任务队列长度
- 考虑使用异步处理避免阻塞

### 3. 监控建议
- 监控API调用成功率
- 监控处理时间
- 监控错误率

## 故障排除

### 常见问题

1. **API调用失败**
   - 检查AccessKey和SecretKey配置
   - 检查网络连接
   - 检查七牛云账户余额

2. **RabbitMQ连接失败**
   - 检查RabbitMQ服务状态
   - 检查连接配置
   - 检查防火墙设置

3. **回调失败**
   - 检查回调URL是否可访问
   - 检查Java端回调接口
   - 检查网络配置

### 日志查看

日志级别可以通过环境变量配置：
```
LOG_LEVEL=DEBUG  # DEBUG, INFO, WARNING, ERROR
```

## 升级说明

### 从模拟算法升级到七牛云API

1. **停止原有算法模拟器**
   ```bash
   # 如果正在运行原有的algorithm_simulator.py，先停止它
   ```

2. **启动七牛云算法模拟器**
   ```bash
   python qiniu_algorithm_simulator.py
   ```

3. **验证功能**
   - 发送测试任务
   - 检查审核结果
   - 验证回调功能

### 回滚到模拟算法

如果需要回滚到模拟算法，只需重新启动原有的算法模拟器：
```bash
python algorithm_simulator.py
```

## 成本估算

使用七牛云API会产生费用，主要计费项：

1. **视频审核**：按视频时长计费
2. **音频审核**：按音频时长计费
3. **文本审核**：按文本量计费

建议在七牛云控制台设置预算提醒。

## 技术支持

- 七牛云官方文档：https://developer.qiniu.com/censor
- 七牛云技术支持：support@qiniu.com
- 项目问题反馈：联系项目维护者

---

**最后更新：2026年1月28日**
**版本：v1.0.0**