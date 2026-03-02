package com.ican.project.service.impl;

import com.ican.project.mapper.UserMapper;
import com.ican.project.model.entity.User;
import com.ican.project.service.MinioService;
import com.ican.project.utils.RedisCacheUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserAvatarServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private RedisCacheUtil redisCacheUtil;

    private UserAvatarServiceImpl userAvatarService;

    @BeforeEach
    public void setUp() {
        userAvatarService = new UserAvatarServiceImpl(userMapper, minioService, redisCacheUtil);
    }

    @Test
    public void shouldUploadAvatarAndUpdateUserRecord() {
        User user = new User();
        user.setId("u-1");
        user.setName("alice");
        user.setEmail("alice@qq.com");
        user.setAvatarPath("avatar/u-1/old.png");

        MockMultipartFile avatar = new MockMultipartFile(
                "avatar",
                "avatar.png",
                "image/png",
                "avatar-content".getBytes()
        );

        when(userMapper.selectById("u-1")).thenReturn(user);
        when(minioService.uploadFile(any(), eq(avatar))).thenReturn("https://cdn.example/avatar-new.png");
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        String avatarUrl = userAvatarService.uploadAvatar("u-1", avatar);

        assertEquals("https://cdn.example/avatar-new.png", avatarUrl);
        verify(minioService).deleteFile("avatar/u-1/old.png");
        verify(userMapper).updateById(any(User.class));
    }

    @Test
    public void shouldRejectNonImageFile() {
        MockMultipartFile invalidFile = new MockMultipartFile(
                "avatar",
                "avatar.txt",
                "text/plain",
                "not image".getBytes()
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> userAvatarService.uploadAvatar("u-1", invalidFile)
        );

        assertEquals("头像文件必须是图片格式", ex.getMessage());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    public void shouldRejectOversizedAvatarFile() {
        byte[] huge = new byte[2 * 1024 * 1024 + 1];
        MockMultipartFile invalidFile = new MockMultipartFile(
                "avatar",
                "avatar.png",
                "image/png",
                huge
        );

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> userAvatarService.uploadAvatar("u-1", invalidFile)
        );

        assertEquals("头像文件不能超过2MB", ex.getMessage());
        verify(userMapper, never()).updateById(any(User.class));
    }
}
