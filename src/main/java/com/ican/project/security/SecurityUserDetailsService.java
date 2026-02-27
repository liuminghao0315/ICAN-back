package com.ican.project.security;

import com.ican.project.mapper.UserMapper;
import com.ican.project.model.entity.User;
import com.ican.project.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SecurityUserDetailsService implements UserDetailsService {
    private static final Logger logger = LoggerFactory.getLogger(SecurityUserDetailsService.class);

    @Autowired
    @Lazy
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.trim().isEmpty()) {
            logger.warn("用户名为空");
            throw new UsernameNotFoundException("用户名为空");
        }

        if (userService == null) {
            logger.error("用户服务未初始化");
            throw new UsernameNotFoundException("用户服务未初始化");
        }

        try {
            User user = userService.getUserByUsername(username);
            if (user == null) {
                logger.debug("用户不存在: username={}", username);
                throw new UsernameNotFoundException("用户不存在: " + username);
            }

            List<String> perms = userMapper.selectPermsByUserId(user.getId());
            if (perms == null) {
                perms = List.of();
            }

            logger.debug("加载用户成功: username={}, userId={}, perms={}", username, user.getId(), perms.size());
            return new MyUserDetails(user, perms);
        } catch (UsernameNotFoundException e) {
            throw e;
        } catch (Exception e) {
            logger.error("加载用户异常: username={}", username, e);
            throw new UsernameNotFoundException("加载用户失败: " + username, e);
        }
    }
}
