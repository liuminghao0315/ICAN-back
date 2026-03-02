package com.ican.project.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 用户头像服务
 */
public interface UserAvatarService {

    /**
     * 上传头像：校验 → 替换旧头像 → 上传 MinIO → 更新数据库
     *
     * @param userId 当前用户ID
     * @param file   图片文件（需为 image/*，≤ 2 MB）
     * @return 可访问的头像 URL
     */
    String uploadAvatar(String userId, MultipartFile file);
}
