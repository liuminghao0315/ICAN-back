#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视频分析算法主模块
实现双向异步事件驱动架构：
1. 监听任务队列（Java -> Python）
2. 模拟媒体分割过程
3. 并发调用视频处理模块和音频处理模块
4. 异步发送结果到结果队列（Python -> Java）
"""

import json
import time
import random
import pika
import logging
import threading
import urllib.parse
from typing import Dict, Any

# 导入子模块
import 视频处理
import 音频处理

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# RabbitMQ 配置
RABBITMQ_HOST = '192.168.178.128'
RABBITMQ_PORT = 5672
RABBITMQ_USERNAME = 'guest'
RABBITMQ_PASSWORD = 'guest'
RABBITMQ_VIRTUAL_HOST = '/'

# 队列名称
TASK_DISPATCH_QUEUE = 'algorithm.task.queue'  # 从 Java 接收任务
RESULT_CALLBACK_QUEUE = 'algorithm.result.queue'  # 向 Java 发送结果

# URL转换配置
TUNNEL_HOST = "5aedd2d8.r12.cpolar.top"
INTERNAL_HOST = "192.168.178.128:9000"

# Whisper模型配置
WHISPER_MODEL_SIZE = "base"  # tiny/base/small/medium/large
WHISPER_DEVICE = "cpu"  # cpu/cuda
WHISPER_COMPUTE_TYPE = "int8"  # int8/float16/float32

# 临时文件目录
TEMP_DIR = "/tmp"


def convert_url_to_tunnel(original_url: str) -> str:
    """
    将Minio内网地址转换为cpolar隧道地址（去除签名查询参数）
    
    Args:
        original_url: http://192.168.178.128:9000/bucket/path/file.mp4?X-Amz-Signature=...
        
    Returns:
        http://5aedd2d8.r12.cpolar.top/bucket/path/file.mp4
    """
    if not original_url:
        return original_url
    
    try:
        # 解析URL
        parsed = urllib.parse.urlparse(original_url)
        
        # 重新构建URL（不含查询参数）
        # 替换netloc（host:port）
        new_netloc = TUNNEL_HOST
        
        # URL decode path
        decoded_path = urllib.parse.unquote(parsed.path)
        
        # 构建新URL（只保留scheme, netloc, path，去掉query参数）
        tunnel_url = urllib.parse.urlunparse((
            "http",          # scheme
            new_netloc,      # netloc (host)
            decoded_path,    # path
            "",              # params
            "",              # query (去掉签名参数)
            ""               # fragment
        ))
        
        logger.info(f"URL转换: {original_url} -> {tunnel_url}")
        return tunnel_url
    except Exception as e:
        logger.error(f"URL转换失败: {e}, 使用原URL")
        return original_url


class AlgorithmSimulator:
    """算法模拟器 - 实现双向异步事件驱动架构"""
    
    def __init__(self):
        """初始化 RabbitMQ 连接"""
        self.connection = None
        self.channel = None
        # 用于存储各模块结果（供综合分析使用）
        self.video_result = None
        self.audio_result = None
        self.text_result = None
        self.connect()
    
    def connect(self, retry_count: int = 0, max_retries: int = 5):
        """
        连接到 RabbitMQ
        支持自动重连机制
        
        Args:
            retry_count: 当前重试次数
            max_retries: 最大重试次数
        """
        try:
            credentials = pika.PlainCredentials(RABBITMQ_USERNAME, RABBITMQ_PASSWORD)
            parameters = pika.ConnectionParameters(
                host=RABBITMQ_HOST,
                port=RABBITMQ_PORT,
                virtual_host=RABBITMQ_VIRTUAL_HOST,
                credentials=credentials,
                connection_attempts=3,  # 连接尝试次数
                retry_delay=2  # 重试延迟（秒）
            )
            self.connection = pika.BlockingConnection(parameters)
            self.channel = self.connection.channel()
            
            # 声明队列
            self.channel.queue_declare(queue=TASK_DISPATCH_QUEUE, durable=True)
            self.channel.queue_declare(queue=RESULT_CALLBACK_QUEUE, durable=True)
            
            logger.info("成功连接到 RabbitMQ")
        except pika.exceptions.AMQPConnectionError as e:
            logger.error(f"连接 RabbitMQ 失败: {e}")
            if retry_count < max_retries:
                wait_time = (retry_count + 1) * 2  # 递增等待时间
                logger.info(f"等待 {wait_time} 秒后重试连接... (尝试 {retry_count + 1}/{max_retries})")
                time.sleep(wait_time)
                return self.connect(retry_count + 1, max_retries)
            else:
                logger.error(f"达到最大重试次数 ({max_retries})，连接失败")
                raise
        except Exception as e:
            logger.error(f"连接 RabbitMQ 时发生未知错误: {e}", exc_info=True)
            raise
    
    def simulate_media_splitting(self, task_id: str, video_info: Dict[str, Any]) -> Dict[str, Any]:
        """
        模拟媒体分割过程
        将视频文件分割为视觉流和音频流
        
        Args:
            task_id: 任务ID
            video_info: 视频信息
            
        Returns:
            分割结果信息
        """
        logger.info(f"[任务 {task_id}] 开始媒体分割...")
        
        # 模拟分割处理时间（1-3秒）
        process_time = random.uniform(1, 3)
        time.sleep(process_time)
        
        result = {
            "visualStreamReady": True,
            "audioStreamReady": True,
            "splitDuration": round(process_time, 2),
            "videoFilePath": video_info.get("videoUrl", ""),
            "fileSize": video_info.get("fileSize", 0)
        }
        
        logger.info(f"[任务 {task_id}] 媒体分割完成，视觉流和音频流已就绪")
        return result
    
    
    def send_result_message(self, task_id: str, module_type: str, result_data: Dict[str, Any], retry_count: int = 0):
        """
        发送模块完成消息到结果回调队列
        支持自动重试机制
        
        Args:
            task_id: 任务ID
            module_type: 模块类型（video/audio/text）
            result_data: 该模块的分析结果数据
            retry_count: 当前重试次数
        """
        message = {
            "taskId": task_id,
            "moduleType": module_type,
            "resultData": result_data
        }
        
        max_retries = 3
        try:
            # 检查连接状态
            if not self.connection or self.connection.is_closed:
                logger.warning(f"[任务 {task_id}] RabbitMQ连接已断开，尝试重连...")
                self.connect()
            
            self.channel.basic_publish(
                exchange='',
                routing_key=RESULT_CALLBACK_QUEUE,
                body=json.dumps(message, ensure_ascii=False),
                properties=pika.BasicProperties(
                    delivery_mode=2,  # 消息持久化
                )
            )
            logger.info(f"[任务 {task_id}] 已发送模块结果消息: {module_type}")
        except (pika.exceptions.AMQPConnectionError, pika.exceptions.ChannelClosed) as e:
            logger.error(f"[任务 {task_id}] 发送消息失败（连接错误）: {e}")
            if retry_count < max_retries:
                logger.info(f"[任务 {task_id}] 等待 {2 ** retry_count} 秒后重试发送... (尝试 {retry_count + 1}/{max_retries})")
                time.sleep(2 ** retry_count)  # 指数退避
                try:
                    self.connect()
                    return self.send_result_message(task_id, module_type, result_data, retry_count + 1)
                except Exception as reconnect_error:
                    logger.error(f"[任务 {task_id}] 重连失败: {reconnect_error}")
                    raise
            else:
                logger.error(f"[任务 {task_id}] 达到最大重试次数 ({max_retries})，发送失败")
                raise
        except Exception as e:
            logger.error(f"[任务 {task_id}] 发送消息失败: {e}", exc_info=True)
            raise
    
    def process_visual_stream(self, task_id: str, task_message: Dict[str, Any]):
        """
        处理视觉流（Thread A）
        调用视频处理模块，完成后发送Video类型消息
        
        Args:
            task_id: 任务ID
            task_message: 任务消息
        """
        try:
            logger.info(f"[任务 {task_id}] [Thread A] 视觉流处理线程启动，调用视频处理模块")
            
            # 调用视频处理模块的函数
            result = 视频处理.process_video(task_id, task_message)
            
            # 保存结果供后续综合分析使用
            self.video_result = result
            
            # 发送Video类型消息
            self.send_result_message(task_id, "video", result)
            
            logger.info(f"[任务 {task_id}] [Thread A] 视觉流处理完成，已发送Video消息")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] [Thread A] 视觉流处理失败: {e}", exc_info=True)
            # 发送错误消息
            error_message = {
                "taskId": task_id,
                "moduleType": "error",
                "resultData": {
                    "error": str(e),
                    "failedModule": "video"
                }
            }
            try:
                self.channel.basic_publish(
                    exchange='',
                    routing_key=RESULT_CALLBACK_QUEUE,
                    body=json.dumps(error_message, ensure_ascii=False),
                    properties=pika.BasicProperties(delivery_mode=2)
                )
            except:
                pass
    
    def process_audio_stream(self, task_id: str, task_message: Dict[str, Any]):
        """
        处理音频流（Thread B）
        调用音频处理模块，顺序执行两个步骤：
        Step 1: 提取音频特征 -> 发送Audio类型消息
        Step 2: ASR转文本 -> 发送Text类型消息
        
        Args:
            task_id: 任务ID
            task_message: 任务消息
        """
        try:
            logger.info(f"[任务 {task_id}] [Thread B] 音频流处理线程启动，调用音频处理模块")
            
            # 调用音频处理模块的函数（返回两个结果：audio_result, text_result）
            audio_result, text_result = 音频处理.process_audio(task_id, task_message)
            
            # 保存结果供后续综合分析使用
            self.audio_result = audio_result
            self.text_result = text_result
            
            # Step 1: 发送Audio类型消息
            self.send_result_message(task_id, "audio", audio_result)
            logger.info(f"[任务 {task_id}] [Thread B] Step 1完成，已发送Audio消息")
            
            # Step 2: 发送Text类型消息
            self.send_result_message(task_id, "text", text_result)
            logger.info(f"[任务 {task_id}] [Thread B] Step 2完成，已发送Text消息")
            
            logger.info(f"[任务 {task_id}] [Thread B] 音频流处理完成，已发送Audio和Text消息")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] [Thread B] 音频流处理失败: {e}", exc_info=True)
            # 发送错误消息
            error_message = {
                "taskId": task_id,
                "moduleType": "error",
                "resultData": {
                    "error": str(e),
                    "failedModule": "audio_stream"
                }
            }
            try:
                self.channel.basic_publish(
                    exchange='',
                    routing_key=RESULT_CALLBACK_QUEUE,
                    body=json.dumps(error_message, ensure_ascii=False),
                    properties=pika.BasicProperties(delivery_mode=2)
                )
            except:
                pass
    
    def calculate_modality_fusion(self, dimension: str, video_score: int, audio_score: int, text_score: int) -> Dict[str, Any]:
        """
        计算单个维度的多模态融合结果
        
        Args:
            dimension: 维度名称
            video_score: 视频模态分数（0-100）
            audio_score: 音频模态分数（0-100）
            text_score: 文本模态分数（0-100）
            
        Returns:
            融合结果（包含各模态贡献度和最终分数）
        """
        # 计算贡献度（基于分数的归一化权重）
        total = video_score + audio_score + text_score
        if total == 0:
            total = 1  # 防止除0
        
        video_contribution = round((video_score / total) * 100, 1)
        audio_contribution = round((audio_score / total) * 100, 1)
        text_contribution = round((text_score / total) * 100, 1)
        
        # 加权融合计算最终分数
        final_score = int(
            (video_score * video_contribution + 
             audio_score * audio_contribution + 
             text_score * text_contribution) / 100
        )
        
        return {
            "videoScore": video_score,
            "audioScore": audio_score,
            "textScore": text_score,
            "videoContribution": video_contribution,
            "audioContribution": audio_contribution,
            "textContribution": text_contribution,
            "finalScore": min(100, max(0, final_score))
        }
    
    def perform_integration_analysis(self, task_id: str) -> Dict[str, Any]:
        """
        执行综合分析（Step 4: Integration → 90%）
        基于video、audio、text三个模块的结果进行融合计算
        
        Args:
            task_id: 任务ID
            
        Returns:
            综合分析结果
        """
        logger.info(f"[任务 {task_id}] ========== 开始执行综合分析（Python） ==========")
        start_time = time.time()
        
        try:
            # 获取三个模块的结果
            video_result = self.video_result
            audio_result = self.audio_result
            text_result = self.text_result
            
            if not video_result or not audio_result or not text_result:
                raise ValueError("模块结果不完整，无法进行综合分析")
            
            # 提取特征数据
            video_features = video_result.get("features", {})
            audio_features = audio_result.get("features", {})
            text_features = text_result.get("features", {})
            
            video_risk_scores = video_features.get("visualRiskScores", {})
            audio_risk_scores = audio_features.get("audioRiskScores", {})
            text_risk_scores = text_features.get("textRiskScores", {})
            
            duration = video_features.get("duration", 60.0)
            time_granularity = 5
            num_segments = int(duration / time_granularity) + 1
            
            # ========== 1. 计算6个维度的多模态融合 ==========
            dimensions = ["identity", "university", "topic", "attitude", "opinionRisk", "action"]
            fusion_results = {}
            
            for dim in dimensions:
                v_score = video_risk_scores.get(dim, 50)
                a_score = audio_risk_scores.get(dim, 50)
                t_score = text_risk_scores.get(dim, 50)
                fusion_results[dim] = self.calculate_modality_fusion(dim, v_score, a_score, t_score)
            
            logger.info(f"[任务 {task_id}] 多模态融合计算完成")
            
            # ========== 2. 计算综合风险时间序列（取三个模态的最大值） ==========
            video_risks = video_result.get("videoRisks", [])
            audio_emotions = audio_result.get("audioEmotions", [])
            text_risks = text_result.get("textRisks", [])
            
            comprehensive_risks = []
            for i in range(num_segments):
                v_intensity = video_risks[i]["intensity"] if i < len(video_risks) else 0.3
                a_intensity = audio_emotions[i]["intensity"] if i < len(audio_emotions) else 0.3
                t_intensity = text_risks[i]["intensity"] if i < len(text_risks) else 0.3
                
                max_intensity = max(v_intensity, a_intensity, t_intensity)
                comprehensive_risks.append({"intensity": round(max_intensity, 2)})
            
            logger.info(f"[任务 {task_id}] 综合风险时间序列计算完成: {len(comprehensive_risks)}个时间段")
            
            # ========== 3. 计算雷达图时间序列（6个维度） ==========
            # 维度说明：[身份置信度, 学校关联度, 负面情感度, 传播风险, 影响范围, 处置紧迫度]
            radar_by_time = []
            for i in range(num_segments):
                # 每个时间段的6个维度分数（简化计算，基于融合结果和时间段风险）
                segment_risk = comprehensive_risks[i]["intensity"]
                
                # 基础分数（来自融合结果）
                identity_base = fusion_results["identity"]["finalScore"]
                university_base = fusion_results["university"]["finalScore"]
                topic_base = fusion_results["topic"]["finalScore"]
                attitude_base = fusion_results["attitude"]["finalScore"]
                opinion_base = fusion_results["opinionRisk"]["finalScore"]
                action_base = fusion_results["action"]["finalScore"]
                
                # 根据时间段风险调整分数
                radar_data = [
                    int(identity_base * (0.8 + segment_risk * 0.4)),  # 身份置信度
                    int(university_base * (0.8 + segment_risk * 0.4)),  # 学校关联度
                    int(attitude_base * (0.7 + segment_risk * 0.6)),  # 负面情感度（受风险影响大）
                    int(topic_base * (0.75 + segment_risk * 0.5)),  # 传播风险
                    int(opinion_base * (0.7 + segment_risk * 0.6)),  # 影响范围
                    int(action_base * (0.75 + segment_risk * 0.5))  # 处置紧迫度
                ]
                
                # 限制在0-100范围
                radar_data = [min(100, max(0, v)) for v in radar_data]
                
                radar_by_time.append({"data": radar_data})
            
            logger.info(f"[任务 {task_id}] 雷达图时间序列计算完成: {len(radar_by_time)}个时间段")
            
            # ========== 4. 计算全片平均雷达数据 ==========
            average_radar_data = [0] * 6
            for radar in radar_by_time:
                for j, value in enumerate(radar["data"]):
                    average_radar_data[j] += value
            average_radar_data = [int(v / len(radar_by_time)) for v in average_radar_data]
            
            logger.info(f"[任务 {task_id}] 平均雷达数据: {average_radar_data}")
            
            # ========== 5. 构建综合分析结果 ==========
            integration_result = {
                # 6个核心维度的多模态融合结果
                "identity": {
                    "identityLabel": self._get_identity_label(fusion_results["identity"]["finalScore"]),
                    "modalityFusion": fusion_results["identity"]
                },
                "university": {
                    "universityName": self._get_university_name(fusion_results["university"]["finalScore"]),
                    "modalityFusion": fusion_results["university"]
                },
                "topic": {
                    "topicCategory": text_features.get("topicCategory", "校园生活"),
                    "topicSubCategory": self._get_topic_sub_category(text_features.get("topicCategory", "")),
                    "modalityFusion": fusion_results["topic"]
                },
                "opinionRisk": {
                    "riskReason": self._get_risk_reason(fusion_results["opinionRisk"]["finalScore"]),
                    "modalityFusion": fusion_results["opinionRisk"]
                },
                "action": {
                    "actionSuggestion": self._get_action_suggestion(fusion_results["action"]["finalScore"]),
                    "actionDetail": self._get_action_detail(fusion_results["action"]["finalScore"]),
                    "modalityFusion": fusion_results["action"]
                },
                
                # 时间轴综合数据
                "timelineData": {
                    "comprehensiveRisks": comprehensive_risks,
                    "radarByTime": radar_by_time,
                    "averageRadarData": average_radar_data
                },
                
                "processingTime": round(time.time() - start_time, 2)
            }
            
            logger.info(f"[任务 {task_id}] 综合分析完成，处理时间: {integration_result['processingTime']:.2f}秒")
            return integration_result
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] 综合分析失败: {e}", exc_info=True)
            raise
    
    def _get_identity_label(self, score: int) -> str:
        """根据分数返回身份标签"""
        if score >= 80:
            return "疑似在校学生"
        elif score >= 60:
            return "可能为学生"
        else:
            return "身份不明"
    
    def _get_university_name(self, score: int) -> str:
        """根据分数返回高校名称"""
        universities = ["北京大学", "清华大学", "复旦大学", "上海交通大学", "浙江大学"]
        if score >= 85:
            return random.choice(universities)
        elif score >= 65:
            return random.choice(universities + ["未明确提及"])
        else:
            return "未识别到明确高校"
    
    def _get_topic_sub_category(self, topic_category: str) -> str:
        """根据主题大类返回细分类"""
        sub_categories = {
            "校园生活": ["日常生活", "宿舍趣事", "校园风景"],
            "学术讨论": ["课程学习", "考试经验", "学术研究"],
            "校园政策": ["选课制度吐槽", "宿舍管理", "食堂服务"],
            "社团活动": ["社团招新", "文艺演出", "社团日常"],
            "就业指导": ["实习分享", "求职经验", "职业规划"],
            "科技创新": ["项目展示", "技术分享", "创新创业"],
            "体育运动": ["运动比赛", "健身日常", "体育活动"],
            "艺术表演": ["音乐表演", "舞蹈展示", "艺术创作"],
            "心理健康": ["心理调适", "情绪管理", "压力释放"],
            "社会实践": ["志愿服务", "社会调研", "公益活动"]
        }
        subs = sub_categories.get(topic_category, ["其他"])
        return random.choice(subs)
    
    def _get_risk_reason(self, score: int) -> str:
        """根据分数返回风险原因"""
        if score >= 70:
            return "可能引发跟风吐槽"
        elif score >= 50:
            return "存在一定传播风险"
        else:
            return "风险较低"
    
    def _get_action_suggestion(self, score: int) -> str:
        """根据分数返回处置建议"""
        if score >= 75:
            return "禁止发布"
        elif score >= 60:
            return "谨慎发布"
        elif score >= 40:
            return "可以发布"
        else:
            return "推荐发布"
    
    def _get_action_detail(self, score: int) -> str:
        """根据分数返回处置详情"""
        if score >= 75:
            return "检测到高风险内容，建议禁止上传到公开平台"
        elif score >= 60:
            return "建议人工复核后决定是否上传"
        elif score >= 40:
            return "内容相对安全，可以发布但建议关注后续反馈"
        else:
            return "内容健康，可以放心发布"
    
    def process_task(self, task_message: Dict[str, Any]):
        """
        处理单个分析任务（5步处理流程）
        1. 模拟媒体分割
        2. 启动两个并行线程：
           - Thread A: 处理视觉流（完成后发送Video消息）
           - Thread B: 处理音频流（顺序执行两个步骤，分别发送Audio和Text消息）
        3. 等待两个线程完成
        4. 执行综合分析（融合计算+雷达图）
        5. 发送Integration消息
        
        Args:
            task_message: 任务消息，包含 taskId, videoId 等信息
        """
        task_id = task_message.get("taskId")
        if not task_id:
            logger.error("任务消息缺少 taskId")
            return
        
        logger.info(f"[任务 {task_id}] ========== 开始处理分析任务 ==========")
        
        try:
            # 重置模块结果
            self.video_result = None
            self.audio_result = None
            self.text_result = None
            
            # Step 0: URL转换
            original_url = task_message.get("videoUrl")
            if original_url:
                task_message["videoUrlInternal"] = original_url
                tunnel_url = convert_url_to_tunnel(original_url)
                task_message["videoUrl"] = tunnel_url
            
            # Step 1: 模拟媒体分割
            split_result = self.simulate_media_splitting(task_id, task_message)
            logger.info(f"[任务 {task_id}] 媒体分割完成: {split_result}")
            
            # Step 2: 启动两个并行线程
            logger.info(f"[任务 {task_id}] 启动两个并行线程处理视觉流和音频流...")
            
            visual_thread = threading.Thread(
                target=self.process_visual_stream,
                args=(task_id, task_message),
                name=f"VisualStream-{task_id}"
            )
            
            audio_thread = threading.Thread(
                target=self.process_audio_stream,
                args=(task_id, task_message),
                name=f"AudioStream-{task_id}"
            )
            
            # 同时启动
            visual_thread.start()
            audio_thread.start()
            logger.info(f"[任务 {task_id}] 两个线程已启动")
            
            # 等待完成
            visual_thread.join()
            audio_thread.join()
            logger.info(f"[任务 {task_id}] 两个线程已完成，已发送3条消息（Video, Audio, Text）")
            
            # Step 3: 执行综合分析
            logger.info(f"[任务 {task_id}] 开始执行综合分析...")
            integration_result = self.perform_integration_analysis(task_id)
            
            # Step 4: 发送Integration消息
            self.send_result_message(task_id, "integration", integration_result)
            logger.info(f"[任务 {task_id}] 已发送Integration消息")
            
            logger.info(f"[任务 {task_id}] ========== Python端处理完成，已发送4条消息（Video, Audio, Text, Integration） ==========")
            
        except Exception as e:
            logger.error(f"[任务 {task_id}] 处理任务失败: {e}", exc_info=True)
    
    def on_task_received(self, ch, method, properties, body):
        """
        处理接收到的任务消息（RabbitMQ回调）
        """
        try:
            message_str = body.decode('utf-8')
            task_message = json.loads(message_str)
            task_id = task_message.get('taskId')
            logger.info(f"收到新任务: taskId={task_id}")
            
            # 处理任务
            self.process_task(task_message)
            
            # 确认消息已处理
            ch.basic_ack(delivery_tag=method.delivery_tag)
            
        except Exception as e:
            logger.error(f"处理任务消息失败: {e}", exc_info=True)
            # 拒绝消息并重新入队（可选）
            ch.basic_nack(delivery_tag=method.delivery_tag, requeue=True)
    
    def start_consuming(self):
        """开始监听任务分发队列"""
        logger.info("=" * 60)
        logger.info("算法模拟器启动")
        logger.info(f"监听队列: {TASK_DISPATCH_QUEUE}")
        logger.info(f"结果队列: {RESULT_CALLBACK_QUEUE}")
        logger.info("=" * 60)
        
        # 设置每次只接收一条消息
        self.channel.basic_qos(prefetch_count=1)
        
        # 开始消费消息
        self.channel.basic_consume(
            queue=TASK_DISPATCH_QUEUE,
            on_message_callback=self.on_task_received
        )
        
        logger.info("等待任务消息...")
        try:
            self.channel.start_consuming()
        except KeyboardInterrupt:
            logger.info("收到中断信号，停止监听")
            self.channel.stop_consuming()
            self.connection.close()
    
    def close(self):
        """关闭连接"""
        if self.connection and not self.connection.is_closed:
            self.connection.close()
            logger.info("RabbitMQ 连接已关闭")


def main():
    """主函数"""
    simulator = AlgorithmSimulator()
    try:
        simulator.start_consuming()
    except Exception as e:
        logger.error(f"运行错误: {e}", exc_info=True)
    finally:
        simulator.close()


if __name__ == '__main__':
    main()
