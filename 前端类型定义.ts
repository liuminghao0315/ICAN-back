/**
 * 高校内容创作者行为分析平台 - 前端TypeScript类型定义
 * 
 * 使用方法：将此文件复制到前端项目的 src/types/ 目录下
 * 然后在需要的地方导入使用
 */

// ==================== 基础类型 ====================

/**
 * 统一响应格式
 */
export interface Result<T = any> {
  code: number;
  message: string | null;
  data: T;
}

/**
 * 分页响应格式
 */
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

// ==================== 用户模块 ====================

/**
 * 登录请求
 */
export interface LoginDTO {
  username: string;
  password: string;
}

/**
 * 注册请求
 */
export interface RegisterDTO {
  username: string;
  password: string;
  email: string;
  verifyCode: string;
}

/**
 * 用户信息（从JWT解析）
 */
export interface UserInfo {
  id: string;
  username: string;
  email?: string;
}

// ==================== 视频模块 ====================

/**
 * 视频状态枚举
 */
export type VideoStatus = 'UPLOADED' | 'ANALYZING' | 'COMPLETED' | 'FAILED';

/**
 * 视频信息
 */
export interface VideoVO {
  id: string;
  title: string;
  description: string | null;
  fileName: string;
  fileSize: number;
  fileType: string;
  duration: number | null;
  width: number | null;
  height: number | null;
  thumbnailUrl: string | null;
  videoUrl: string;
  status: VideoStatus;
  gmtCreated: string;
}

/**
 * 分片上传检查响应
 */
export interface ChunkCheckResult {
  needUpload: boolean;
  uploadedChunks: number[];
  finished: boolean;
  videoId?: string;
  videoUrl?: string;
  message: string;
}

/**
 * 分片上传请求参数
 */
export interface ChunkUploadParams {
  chunk: File;
  fileIdentifier: string;
  fileName: string;
  chunkNumber: number;
  totalChunks: number;
  chunkSize: number;
  totalSize: number;
  title?: string;
  description?: string;
}

// ==================== 分析任务模块 ====================

/**
 * 任务类型枚举
 */
export type TaskType = 'FULL_ANALYSIS' | 'VIDEO_ONLY' | 'AUDIO_ONLY' | 'TEXT_ONLY';

/**
 * 任务状态枚举
 */
export type TaskStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

/**
 * 创建分析任务请求
 */
export interface AnalysisTaskDTO {
  videoId: string;
  taskType?: TaskType;
}

/**
 * 分析任务信息
 */
export interface AnalysisTaskVO {
  id: string;
  videoId: string;
  videoTitle: string;
  videoUrl: string;
  taskType: TaskType;
  status: TaskStatus;
  progress: number;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
  gmtCreated: string;
  hasResult: boolean;
  resultId: string | null;
}

// ==================== 分析结果模块 ====================

/**
 * 风险等级枚举
 */
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

/**
 * 情感标签枚举
 */
export type SentimentLabel = 'POSITIVE' | 'NEUTRAL' | 'NEGATIVE';

/**
 * 视频特征
 */
export interface VideoFeatures {
  duration: number;
  width: number;
  height: number;
  fps: number;
  sceneType: string;
  sceneConfidence: number;
  faceCount: number;
  hasPerson: boolean;
  qualityScore: number;
  brightness: number;
  clarity: number;
}

/**
 * 音频特征
 */
export interface AudioFeatures {
  hasAudio: boolean;
  audioQuality: number;
  speechRatio: number;
  musicRatio: number;
  noiseLevel: number;
  volumeLevel: number;
  emotionInVoice: string;
}

/**
 * 文本特征
 */
export interface TextFeatures {
  titleLength: number;
  hasDescription: boolean;
  descriptionLength: number;
  titleSentiment: number;
  containsKeywords: boolean;
  languageConfidence: number;
}

/**
 * 受众分析
 */
export interface AudienceAnalysis {
  ageDistribution: {
    '18-24': number;
    '25-34': number;
    '35-44': number;
    '45+': number;
  };
  predictedInterests: string[];
  predictedViews: number;
  predictedEngagement: number;
}

/**
 * 分析结果信息
 */
export interface AnalysisResultVO {
  id: string;
  taskId: string;
  videoId: string;
  videoTitle: string;
  videoUrl: string;
  
  // 风险评估
  riskScore: number;
  riskLevel: RiskLevel;
  riskLevelDesc: string;
  
  // 高校识别
  isUniversityRelated: boolean;
  universityName: string | null;
  universityConfidence: number | null;
  
  // 主题分析
  topicCategory: string;
  topicKeywords: string[];
  
  // 情感分析
  sentimentScore: number;
  sentimentLabel: SentimentLabel;
  sentimentLabelDesc: string;
  
  // 多模态特征
  videoFeatures: VideoFeatures;
  audioFeatures: AudioFeatures;
  transcription: string;
  textFeatures: TextFeatures;
  
  // 受众预测
  audienceAnalysis: AudienceAnalysis;
  spreadPotential: number;
  
  gmtCreated: string;
}

/**
 * 分析统计数据
 */
export interface AnalysisStats {
  totalVideos: number;
  analyzedVideos: number;
  totalResults: number;
  avgRiskScore: number;
  highRiskCount: number;
  mediumRiskCount: number;
  lowRiskCount: number;
}

/**
 * 风险分布
 */
export interface RiskDistribution {
  HIGH: number;
  MEDIUM: number;
  LOW: number;
}

// ==================== 统计模块 ====================

/**
 * 上传趋势数据点
 */
export interface UploadTrendItem {
  date: string;
  count: number;
  totalSize: number;
}

/**
 * 总体统计数据
 */
export interface TotalStats {
  totalUploadCount: number;
  totalUploadSize: number;
  todayUploadCount: number;
  todayUploadSize: number;
}

// ==================== WebSocket消息 ====================

/**
 * WebSocket消息类型
 */
export type WSMessageType = 'connected' | 'task_progress' | 'task_completed' | 'task_failed' | 'pong';

/**
 * WebSocket基础消息
 */
export interface WSMessage<T = any> {
  type: WSMessageType;
  message: string;
  timestamp: number;
  data?: T;
}

/**
 * 任务进度消息数据
 */
export interface TaskProgressData {
  taskId: string;
  status: TaskStatus;
  progress: number;
  message: string;
}

/**
 * 任务完成消息数据
 */
export interface TaskCompletedData {
  taskId: string;
  resultId: string;
}

/**
 * 任务失败消息数据
 */
export interface TaskFailedData {
  taskId: string;
  errorMessage: string;
}

// ==================== API接口定义 ====================

/**
 * API接口路径常量
 */
export const API_PATHS = {
  // 认证
  AUTH: {
    LOGIN: '/auth/login',
    REGISTER: '/auth/register',
    SEND_MAIL: '/auth/sendMailToRegister',
  },
  // 账户
  ACCOUNT: {
    LOGOUT: '/account/logout',
    SEND_RESET_MAIL: '/account/sendMailToResetPwd',
    RESET_PWD: '/account/resetPwd',
  },
  // 视频
  VIDEO: {
    UPLOAD: '/api/video/upload',
    CHECK_CHUNK: '/api/video/check-chunk',
    UPLOAD_CHUNK: '/api/video/upload-chunk',
    LIST: '/api/video/list',
    DETAIL: (id: string) => `/api/video/${id}`,
    DELETE: (id: string) => `/api/video/${id}`,
  },
  // 分析任务
  TASK: {
    CREATE: '/api/analysis/task',
    LIST: '/api/analysis/task/list',
    DETAIL: (id: string) => `/api/analysis/task/${id}`,
    BY_VIDEO: (videoId: string) => `/api/analysis/task/video/${videoId}`,
    CANCEL: (id: string) => `/api/analysis/task/${id}/cancel`,
    RETRY: (id: string) => `/api/analysis/task/${id}/retry`,
  },
  // 分析结果
  RESULT: {
    DETAIL: (id: string) => `/api/analysis/result/${id}`,
    BY_TASK: (taskId: string) => `/api/analysis/result/task/${taskId}`,
    BY_VIDEO: (videoId: string) => `/api/analysis/result/video/${videoId}`,
    LIST: '/api/analysis/result/list',
    STATS: '/api/analysis/result/stats',
    RISK_DISTRIBUTION: '/api/analysis/result/risk-distribution',
    DELETE: (id: string) => `/api/analysis/result/${id}`,
  },
  // 统计
  STATISTICS: {
    UPLOAD_TREND: '/api/statistics/upload-trend',
    TOTAL: '/api/statistics/total',
  },
  // WebSocket
  WS: {
    TASK_PROGRESS: (userId: string) => `ws://localhost:8080/ws/task-progress/${userId}`,
  },
} as const;

// ==================== 工具类型 ====================

/**
 * 风险等级颜色映射
 */
export const RISK_LEVEL_COLORS: Record<RiskLevel, string> = {
  LOW: '#52c41a',      // 绿色
  MEDIUM: '#faad14',   // 黄色
  HIGH: '#f5222d',     // 红色
};

/**
 * 风险等级中文映射
 */
export const RISK_LEVEL_TEXT: Record<RiskLevel, string> = {
  LOW: '低风险',
  MEDIUM: '中风险',
  HIGH: '高风险',
};

/**
 * 任务状态颜色映射
 */
export const TASK_STATUS_COLORS: Record<TaskStatus, string> = {
  PENDING: '#1890ff',    // 蓝色
  PROCESSING: '#faad14', // 黄色
  COMPLETED: '#52c41a',  // 绿色
  FAILED: '#f5222d',     // 红色
  CANCELLED: '#d9d9d9',  // 灰色
};

/**
 * 任务状态中文映射
 */
export const TASK_STATUS_TEXT: Record<TaskStatus, string> = {
  PENDING: '排队中',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
};

/**
 * 情感标签颜色映射
 */
export const SENTIMENT_COLORS: Record<SentimentLabel, string> = {
  POSITIVE: '#52c41a',  // 绿色
  NEUTRAL: '#1890ff',   // 蓝色
  NEGATIVE: '#f5222d',  // 红色
};

/**
 * 情感标签中文映射
 */
export const SENTIMENT_TEXT: Record<SentimentLabel, string> = {
  POSITIVE: '正面',
  NEUTRAL: '中性',
  NEGATIVE: '负面',
};

/**
 * 视频状态中文映射
 */
export const VIDEO_STATUS_TEXT: Record<VideoStatus, string> = {
  UPLOADED: '已上传',
  ANALYZING: '分析中',
  COMPLETED: '已完成',
  FAILED: '分析失败',
};

/**
 * 任务类型中文映射
 */
export const TASK_TYPE_TEXT: Record<TaskType, string> = {
  FULL_ANALYSIS: '完整分析',
  VIDEO_ONLY: '仅视频分析',
  AUDIO_ONLY: '仅音频分析',
  TEXT_ONLY: '仅文本分析',
};

// ==================== 工具函数类型 ====================

/**
 * 文件大小格式化
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

/**
 * 时长格式化（秒转为 mm:ss 或 hh:mm:ss）
 */
export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) {
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  }
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}

/**
 * 日期格式化
 */
export function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * 风险评分格式化（0-1 转为 百分比）
 */
export function formatRiskScore(score: number): string {
  return `${(score * 100).toFixed(1)}%`;
}

/**
 * 情感评分格式化（-1到1 转为 描述）
 */
export function formatSentimentScore(score: number): string {
  if (score >= 0.3) return `正面 (${(score * 100).toFixed(0)}%)`;
  if (score <= -0.3) return `负面 (${(Math.abs(score) * 100).toFixed(0)}%)`;
  return `中性`;
}

