package com.ican.project.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * MinIO 对象存储服务接口
 */
public interface MinioService {
    
    /**
     * 检查桶是否存在，不存在则创建
     */
    void ensureBucketExists();
    
    /**
     * 上传文件
     * @param objectName 对象名称（存储路径）
     * @param file 文件
     * @return 文件访问URL
     */
    String uploadFile(String objectName, MultipartFile file);
    
    /**
     * 上传文件流
     * @param objectName 对象名称
     * @param inputStream 输入流
     * @param contentType 内容类型
     * @param size 文件大小
     * @return 文件访问URL
     */
    String uploadFile(String objectName, InputStream inputStream, String contentType, long size);
    
    /**
     * 获取文件访问URL
     * @param objectName 对象名称
     * @return 预签名URL
     */
    String getFileUrl(String objectName);
    
    /**
     * 删除文件
     * @param objectName 对象名称
     */
    void deleteFile(String objectName);
    
    /**
     * 检查文件是否存在
     * @param objectName 对象名称
     * @return 是否存在
     */
    boolean fileExists(String objectName);
}

