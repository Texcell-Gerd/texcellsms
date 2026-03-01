package com.texcellsms.sms.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 用户信息工具类
 */
public class UserUtils {

    /**
     * 获取当前登录用户的subAccount（提交帐号）
     */
    public static String getCurrentSubAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new RuntimeException("未登录，无法获取subAccount");
        }
        Object principal = authentication.getPrincipal();
        String subAccount;
        if (principal instanceof UserDetails) {
            // UserDetails实现类中存储了subAccount，直接获取
            subAccount = ((UserDetails) principal).getUsername(); // 假设username就是subAccount，若不是则调整
        } else {
            subAccount = principal.toString();
        }
        return subAccount;
    }
}