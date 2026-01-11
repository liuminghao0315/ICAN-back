# 使用官方Python 3.9镜像作为基础镜像
FROM python:3.9

# 设置工作目录
WORKDIR /app

# 设置环境变量
ENV PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8

# 复制requirements.txt文件
COPY requirements.txt .

# 安装Python依赖
RUN pip install --no-cache-dir -r requirements.txt

# 复制Python源代码文件
COPY algorithm_simulator.py .
COPY 视频处理.py .
COPY 音频处理.py .

# 设置Python文件编码为UTF-8（支持中文文件名）
ENV PYTHONIOENCODING=utf-8

# 暴露端口（如果需要的话，虽然这个服务主要是RabbitMQ消费者）
# EXPOSE 5000

# 设置入口点
CMD ["python", "algorithm_simulator.py"]
