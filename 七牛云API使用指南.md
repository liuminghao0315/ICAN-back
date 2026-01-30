# 七牛云API使用指南

## 快速开始

### 1. 安装依赖
```bash
pip install -r requirements.txt
```

### 2. 配置密钥
```bash
# 复制配置文件
copy .env.example .env

# 编辑配置文件，填入七牛云密钥
# 打开 .env 文件，修改以下配置：
# QINIU_ACCESS_KEY=你的AccessKey
# QINIU_SECRET_KEY=你的SecretKey
```

### 3. 启动服务
```bash
# Windows
start_qiniu_algorithm.bat

# Linux/Mac
python qiniu_algorithm_simulator.py
```

## 模块详解

### 1. 配置模块 (qiniu_config.py)

#### 核心类
- `QiniuConfig`: 配置管理类
- `QiniuClient`: API客户端封装

#### 配置项
```python
# 从环境变量读取
ACCESS_KEY = os.environ.get('QINIU_ACCESS_KEY', 'your_access_key_here')
SECRET_KEY = os.environ.get('QINIU_SECRET_KEY', 'your_secret_key_here')

# API端点
VIDEO_CENSOR_URL = 'http://ai.qiniuapi.com/v3/video/censor'
AUDIO_CENSOR_URL = 'http://ai.qiniuapi.com/v3/audio/censor'
TEXT_CENSOR_URL = 'http://ai.qiniuapi.com/v3/text/censor'
```

#### 使用方法
```python
from qiniu_config import get_qiniu_client

# 获取客户端
client = get_qiniu_client()

# 验证配置
if client is None:
    print("七牛云配置无效")
```

### 2. 视频审核模块 (qiniu_video_processor.py)

#### 核心函数
```python
from qiniu_video_processor import process_video

# 处理视频审核
result = process_video(task_id, video_info)
```

#### 输入参数
```python
video_info = {
    "videoUrl": "http://example.com/video.mp4",
    "videoTitle": "视频标题",
    "videoDuration": 120.5,  # 秒
    "fileSize": 10240000     # 字节
}
```

#### 输出结果
```json
{
  "duration": 120.5,
  "width": 1920,
  "height": 1080,
  "fps": 30,
  "riskScore": 0.25,
  "riskLevel": "LOW",
  "hasViolation": false,
  "pulpScore": 0.1,
  "pulpLabel": "normal",
  "pulpSuggestion": "pass",
  "terrorScore": 0.05,
  "terrorLabel": "normal",
  "terrorSuggestion": "pass",
  "politicianScore": 0.15,
  "politicianLabel": "normal",
  "politicianSuggestion": "pass",
  "adsScore": 0.08,
  "adsLabel": "normal",
  "adsSuggestion": "pass",
  "qualityScore": 0.8,
  "brightness": 0.5,
  "clarity": 0.8,
  "processingTime": 2.5,
  "apiJobId": "job_123456",
  "apiStatus": "success",
  "source": "qiniu_video_censor"
}
```

### 3. 音频审核模块 (qiniu_audio_processor.py)

#### 核心函数
```python
from qiniu_audio_processor import extract_audio_features

# 处理音频审核
result = extract_audio_features(task_id, video_info)
```

#### 输出结果
```json
{
  "hasAudio": true,
  "audioDuration": 120.5,
  "riskScore": 0.15,
  "riskLevel": "LOW",
  "hasViolation": false,
  "antispamScore": 0.1,
  "antispamLabel": "normal",
  "antispamSuggestion": "pass",
  "audioQuality": 0.7,
  "speechRatio": 0.6,
  "musicRatio": 0.2,
  "noiseLevel": 0.1,
  "volumeLevel": 0.7,
  "emotionInVoice": "neutral",
  "processingTime": 1.8,
  "apiJobId": "job_789012",
  "apiStatus": "success",
  "source": "qiniu_audio_censor"
}
```

### 4. 文本审核模块 (qiniu_text_processor.py)

#### 核心函数
```python
from qiniu_text_processor import convert_audio_to_text

# 处理文本审核
result = convert_audio_to_text(task_id, video_info)
```

#### 输出结果
```json
{
  "textLength": 50,
  "originalText": "这是一段测试文本...",
  "hasText": true,
  "riskScore": 0.08,
  "riskLevel": "LOW",
  "hasViolation": false,
  "antispamScore": 0.05,
  "antispamLabel": "normal",
  "antispamSuggestion": "pass",
  "sentimentScore": 0.3,
  "sentimentLabel": "POSITIVE",
  "topicCategory": "校园生活",
  "keywords": ["大学生", "校园", "学习"],
  "languageConfidence": 0.95,
  "processingTime": 1.2,
  "apiJobId": "job_345678",
  "apiStatus": "success",
  "source": "qiniu_text_censor"
}
```

## 高级配置

### 1. 自定义审核场景

修改 `qiniu_config.py` 中的 `DEFAULT_CONFIG`：

```python
DEFAULT_CONFIG = {
    'video': {
        'scenes': ['pulp', 'terror', 'politician', 'ads'],  # 自定义场景
        'async': True,
        'callback': 'http://your-callback-url.com/callback',
        'callback_body': '{"taskId":"$(x:taskId)","moduleType":"video","resultData":$(avinfo)}',
        'callback_body_type': 'application/json'
    },
    # 其他配置...
}
```

### 2. 同步审核模式

```python
# 修改配置为同步审核
config = QiniuConfig.get_config('video')
config['async'] = False
# 移除回调配置
del config['callback']
del config['callback_body']
del config['callback_body_type']
```

### 3. 自定义回调处理

Java端需要实现回调接口来处理七牛云的回调结果。回调接口地址默认为：
```
http://localhost:8080/api/algorithm/callback
```

## 错误处理

### 1. 配置错误
```python
try:
    client = get_qiniu_client()
    if client is None:
        print("七牛云配置无效，请检查.env文件")
except Exception as e:
    print(f"配置错误: {e}")
```

### 2. API调用错误
```python
try:
    result = process_video(task_id, video_info)
    if result.get('apiStatus') == 'failed':
        print(f"API调用失败: {result.get('error')}")
except Exception as e:
    print(f"处理错误: {e}")
```

### 3. 网络错误
系统内置了自动重试机制：
- 连接失败：自动重连（最多5次）
- API调用失败：自动重试（最多3次）
- 指数退避策略：避免频繁重试

## 性能优化

### 1. 并发处理
系统支持多任务并发处理，建议配置：
- 任务队列长度：根据系统资源调整
- 并发线程数：建议不超过CPU核心数的2倍

### 2. 缓存策略
- API响应缓存：减少重复调用
- 配置缓存：避免重复读取配置文件

### 3. 监控指标
建议监控以下指标：
- API调用成功率
- 平均处理时间
- 错误率
- 并发任务数

## 集成测试

### 1. 单元测试
```bash
# 测试视频审核模块
python qiniu_video_processor.py

# 测试音频审核模块
python qiniu_audio_processor.py

# 测试文本审核模块
python qiniu_text_processor.py
```

### 2. 集成测试步骤
1. 启动RabbitMQ服务
2. 启动七牛云算法模拟器
3. 通过Java端发送测试任务
4. 验证审核结果
5. 检查回调处理

### 3. 测试数据
```python
test_video_info = {
    "videoUrl": "http://example.com/test.mp4",
    "videoTitle": "测试视频",
    "videoDuration": 60.0,
    "fileSize": 5242880  # 5MB
}
```

## 故障排除

### 常见问题及解决方案

| 问题 | 可能原因 | 解决方案 |
|------|----------|----------|
| API调用失败 | 密钥配置错误 | 检查.env文件中的AccessKey和SecretKey |
| 网络连接失败 | 防火墙或网络配置 | 检查网络连接和防火墙设置 |
| 回调失败 | 回调URL不可访问 | 确保回调接口正常运行 |
| 处理超时 | 视频文件过大 | 优化文件大小或增加超时时间 |
| 结果异常 | API响应格式变化 | 更新解析逻辑 |

### 日志分析

启用DEBUG级别日志：
```bash
# 设置环境变量
set LOG_LEVEL=DEBUG

# 或者直接修改代码
import logging
logging.basicConfig(level=logging.DEBUG)
```

### 性能监控

建议监控以下指标：
- CPU使用率
- 内存使用率
- 网络带宽
- 磁盘IO

## 部署建议

### 1. 环境要求
- Python 3.7+
- RabbitMQ 3.8+
- 稳定的网络连接

### 2. 安全建议
- 保护.env文件，不要提交到版本控制
- 定期更换AccessKey和SecretKey
- 配置适当的访问权限

### 3. 备份策略
- 定期备份配置文件
- 备份审核结果数据
- 建立灾难恢复计划

## 技术支持

### 1. 七牛云官方资源
- 文档中心：https://developer.qiniu.com/censor
- 技术支持：support@qiniu.com
- 社区论坛：https://segmentfault.com/qiniu

### 2. 项目支持
- 问题反馈：联系项目维护者
- 功能建议：提交Issue
- 贡献代码：提交Pull Request

---

**更新记录**
- v1.0.0 (2026-01-28): 初始版本，集成七牛云API

**注意事项**
1. 七牛云API会产生费用，请合理使用
2. 建议在生产环境前进行充分测试
3. 定期更新依赖包以确保安全