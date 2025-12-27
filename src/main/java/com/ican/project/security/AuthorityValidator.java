package com.ican.project.security;

import com.ican.project.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("myValidator")
public class AuthorityValidator {
    @Autowired
    private UserService userService;

    public boolean validateAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        MyUserDetails user = (MyUserDetails) authentication.getPrincipal();
        user.setAuthorityList(userService.getUserPermissions(user.getUser().getId()));
        List<String> authorityList = user.getAuthorityList();
        return authorityList.contains(authority);
    }
}
