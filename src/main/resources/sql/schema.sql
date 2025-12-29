-- =============================================
-- 高校内容创作者行为分析平台 数据库设计
-- 版本: V1.0
-- =============================================

-- 用户表（已存在，这里作为参考）
-- CREATE TABLE IF NOT EXISTS `user` (
--     `id` VARCHAR(36) PRIMARY KEY,
--     `name` VARCHAR(50) NOT NULL UNIQUE,
--     `password` VARCHAR(255) NOT NULL,
--     `email` VARCHAR(100) NOT NULL UNIQUE,
--     `analysis_count` INT DEFAULT 0 COMMENT '累计分析次数（只增不减）',
--     `gmt_created` DATETIME DEFAULT CURRENT_TIMESTAMP,
--     `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 为已存在的用户表添加 analysis_count 字段（如果不存在）
-- ALTER TABLE `user` ADD COLUMN IF NOT EXISTS `analysis_count` INT DEFAULT 0 COMMENT '累计分析次数（只增不减）';

-- 视频表
CREATE TABLE IF NOT EXISTS `video` (
    `id` VARCHAR(36) PRIMARY KEY COMMENT '视频唯一ID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '上传用户ID',
    `title` VARCHAR(200) NOT NULL COMMENT '视频标题',
    `description` TEXT COMMENT '视频描述',
    `file_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_path` VARCHAR(500) NOT NULL COMMENT 'MinIO存储路径',
    `file_size` BIGINT NOT NULL COMMENT '文件大小（字节）',
    `file_type` VARCHAR(50) COMMENT '文件类型（如 video/mp4）',
    `duration` DOUBLE COMMENT '视频时长（秒）',
    `width` INT COMMENT '视频宽度',
    `height` INT COMMENT '视频高度',
    `thumbnail_path` VARCHAR(500) COMMENT '缩略图路径',
    `status` VARCHAR(20) DEFAULT 'UPLOADED' COMMENT '状态: UPLOADED/ANALYZING/COMPLETED/FAILED',
    `gmt_created` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_gmt_created` (`gmt_created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频信息表';

-- 分析任务表
CREATE TABLE IF NOT EXISTS `analysis_task` (
    `id` VARCHAR(36) PRIMARY KEY COMMENT '任务ID',
    `video_id` VARCHAR(36) NOT NULL COMMENT '关联视频ID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '创建用户ID',
    `task_type` VARCHAR(50) DEFAULT 'FULL_ANALYSIS' COMMENT '任务类型: FULL_ANALYSIS/VIDEO_ONLY/AUDIO_ONLY/TEXT_ONLY',
    `status` VARCHAR(20) DEFAULT 'PENDING' COMMENT '任务状态: PENDING/PROCESSING/COMPLETED/FAILED/CANCELLED',
    `progress` INT DEFAULT 0 COMMENT '处理进度(0-100)',
    `error_message` TEXT COMMENT '错误信息',
    `started_at` DATETIME COMMENT '开始处理时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `gmt_created` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    INDEX `idx_video_id` (`video_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`),
    FOREIGN KEY (`video_id`) REFERENCES `video`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分析任务表';

-- 分析结果表
CREATE TABLE IF NOT EXISTS `analysis_result` (
    `id` VARCHAR(36) PRIMARY KEY COMMENT '结果ID',
    `task_id` VARCHAR(36) NOT NULL COMMENT '关联任务ID',
    `video_id` VARCHAR(36) NOT NULL COMMENT '关联视频ID',
    
    -- 综合风险评估
    `risk_score` DECIMAL(5,4) COMMENT '综合风险评分(0-1)',
    `risk_level` VARCHAR(20) COMMENT '风险等级: LOW/MEDIUM/HIGH',
    
    -- 高校身份识别
    `is_university_related` TINYINT(1) DEFAULT 0 COMMENT '是否高校相关',
    `university_name` VARCHAR(100) COMMENT '识别到的高校名称',
    `university_confidence` DECIMAL(5,4) COMMENT '高校识别置信度',
    
    -- 内容主题
    `topic_category` VARCHAR(50) COMMENT '主题分类',
    `topic_keywords` JSON COMMENT '主题关键词列表',
    
    -- 情感分析
    `sentiment_score` DECIMAL(5,4) COMMENT '情感评分(-1到1，负面到正面)',
    `sentiment_label` VARCHAR(20) COMMENT '情感标签: POSITIVE/NEUTRAL/NEGATIVE',
    
    -- 视频特征
    `video_features` JSON COMMENT '视频特征（场景、人脸等）',
    
    -- 音频特征  
    `audio_features` JSON COMMENT '音频特征（语音转文字、情感等）',
    `transcription` TEXT COMMENT '语音转文字结果',
    
    -- 文本特征
    `text_features` JSON COMMENT '文本特征（标题、描述分析）',
    
    -- 受众预测
    `audience_analysis` JSON COMMENT '受众分析结果',
    
    -- 传播预测
    `spread_potential` DECIMAL(5,4) COMMENT '传播潜力评分(0-1)',
    
    `gmt_created` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_video_id` (`video_id`),
    INDEX `idx_risk_level` (`risk_level`),
    FOREIGN KEY (`task_id`) REFERENCES `analysis_task`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`video_id`) REFERENCES `video`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分析结果表';

-- 上传统计表（持久化每天的上传数量，即使视频被删除也保留统计）
CREATE TABLE IF NOT EXISTS `upload_statistics` (
    `id` VARCHAR(36) PRIMARY KEY COMMENT '统计ID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '用户ID',
    `stat_date` DATE NOT NULL COMMENT '统计日期',
    `upload_count` INT DEFAULT 0 COMMENT '上传数量',
    `total_size` BIGINT DEFAULT 0 COMMENT '总上传大小（字节）',
    `gmt_created` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    
    UNIQUE KEY `uk_user_date` (`user_id`, `stat_date`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上传统计表';

-- 分片上传记录表（用于断点续传）
CREATE TABLE IF NOT EXISTS `upload_chunk` (
    `id` VARCHAR(36) PRIMARY KEY COMMENT '分片ID',
    `file_identifier` VARCHAR(100) NOT NULL COMMENT '文件唯一标识（MD5）',
    `user_id` VARCHAR(36) NOT NULL COMMENT '上传用户ID',
    `file_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `total_chunks` INT NOT NULL COMMENT '总分片数',
    `chunk_number` INT NOT NULL COMMENT '当前分片序号',
    `chunk_size` BIGINT NOT NULL COMMENT '分片大小',
    `total_size` BIGINT NOT NULL COMMENT '文件总大小',
    `chunk_path` VARCHAR(500) COMMENT '分片临时存储路径',
    `is_uploaded` TINYINT(1) DEFAULT 0 COMMENT '是否已上传',
    `gmt_created` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    UNIQUE KEY `uk_file_chunk` (`file_identifier`, `chunk_number`),
    INDEX `idx_file_identifier` (`file_identifier`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分片上传记录表';

