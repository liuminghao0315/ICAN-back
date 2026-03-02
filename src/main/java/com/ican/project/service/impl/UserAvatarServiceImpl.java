package com.ican.project.service.impl;

import com.ican.project.exception.BusinessException;
import com.ican.project.mapper.UserMapper;
import com.ican.project.model.entity.User;
import com.ican.project.service.MinioService;
import com.ican.project.service.UserAvatarService;
import com.ican.project.utils.RedisCacheUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 用户头像服务实现
 * 校验 → 删除旧头像（可选）→ 上传 MinIO → 更新 DB / 缓存
 */
@Service
public class UserAvatarServiceImpl implements UserAvatarService {

    private static final Logger logger = LoggerFactory.getLogger(UserAvatarServiceImpl.class);

    private static final long MAX_SIZE_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    private final UserMapper userMapper;
    private final MinioService minioService;
    private final RedisCacheUtil redisCacheUtil;

    public UserAvatarServiceImpl(UserMapper userMapper,
                                  MinioService minioService,
                                  RedisCacheUtil redisCacheUtil) {
        this.userMapper = userMapper;
        this.minioService = minioService;
        this.redisCacheUtil = redisCacheUtil;
    }

    @Override
    public String uploadAvatar(String userId, MultipartFile file) {
        validateFile(file);

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 删除旧头像（可选，失败不影响主流程）
        String oldPath = user.getAvatarPath();
        if (oldPath != null && !oldPath.isBlank()) {
            try {
                minioService.deleteFile(oldPath);
            } catch (Exception e) {
                logger.warn("删除旧头像失败，忽略: path={}, err={}", oldPath, e.getMessage());
            }
        }

        // 生成新路径并上传
        String ext = resolveExt(file.getContentType());
        String objectPath = "avatar/" + userId + "/avatar" + ext;
        String avatarUrl = minioService.uploadFile(objectPath, file);

        // 更新数据库
        user.setAvatarPath(objectPath);
        user.tackleTime();
        userMapper.updateById(user);

        // 清除用户信息缓存，确保 /account/me 返回最新数据
        redisCacheUtil.delete(RedisCacheUtil.CacheKey.USER_BY_USERNAME + user.getName());

        logger.info("头像更新成功: userId={}, path={}", userId, objectPath);
        return avatarUrl;
    }

    // ── 私有工具 ────────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("头像文件不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("头像文件必须是图片格式");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("头像文件不能超过2MB");
        }
    }

    private String resolveExt(String contentType) {
        if (contentType == null) return ".jpg";
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
