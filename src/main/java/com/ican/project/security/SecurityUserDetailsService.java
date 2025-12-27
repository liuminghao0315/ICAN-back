package com.ican.project.security;

import com.ican.project.mapper.UserMapper;
import com.ican.project.model.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SecurityUserDetailsService implements UserDetailsService {
    @Autowired
    private UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<User> users = userMapper.selectByMap(Map.of("name", username));
        if(users == null || users.isEmpty()) {
            throw new UsernameNotFoundException(username);
        }
        User user = users.get(0);
        return new MyUserDetails(user,List.of());
    }
}
