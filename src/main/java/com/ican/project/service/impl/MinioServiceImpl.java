package com.ican.project.service.impl;

import com.ican.project.exception.BusinessException;
import com.ican.project.service.MinioService;
import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 服务实现
 */
@Service
public class MinioServiceImpl implements MinioService {
    
    private static final Logger logger = LoggerFactory.getLogger(MinioServiceImpl.class);
    
    @Autowired
    private MinioClient minioClient;
    
    @Value("${minio.bucketName}")
    private String bucketName;
    
    @Value("${minio.endpoint}")
    private String endpoint;
    
    @PostConstruct
    public void init() {
        ensureBucketExists();
    }
    
    @Override
    public void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucketName)
                    .build());
            
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucketName)
                        .build());
                logger.info("创建MinIO桶: {}", bucketName);
            }
        } catch (Exception e) {
            logger.error("检查/创建MinIO桶失败: {}", e.getMessage());
            // 不抛出异常，允许应用启动（MinIO可能稍后才可用）
        }
    }
    
    @Override
    public String uploadFile(String objectName, MultipartFile file) {
        try {
            return uploadFile(objectName, file.getInputStream(), 
                    file.getContentType(), file.getSize());
        } catch (Exception e) {
            logger.error("上传文件失败: {}", e.getMessage(), e);
            throw new BusinessException("上传文件失败: " + e.getMessage());
        }
    }
    
    @Override
    public String uploadFile(String objectName, InputStream inputStream, 
                            String contentType, long size) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build());
            
            logger.info("文件上传成功: {}", objectName);
            return getFileUrl(objectName);
        } catch (Exception e) {
            logger.error("上传文件失败: {}", e.getMessage(), e);
            throw new BusinessException("上传文件失败: " + e.getMessage());
        }
    }
    
    @Override
    public String getFileUrl(String objectName) {
        try {
            // 生成7天有效的预签名URL
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .method(Method.GET)
                    .expiry(7, TimeUnit.DAYS)
                    .build());
        } catch (Exception e) {
            logger.error("获取文件URL失败: {}", e.getMessage(), e);
            // 返回直接访问URL
            return endpoint + "/" + bucketName + "/" + objectName;
        }
    }
    
    @Override
    public void deleteFile(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            logger.info("文件删除成功: {}", objectName);
        } catch (Exception e) {
            logger.error("删除文件失败: {}", e.getMessage(), e);
            throw new BusinessException("删除文件失败: " + e.getMessage());
        }
    }
    
    @Override
    public boolean fileExists(String objectName) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

