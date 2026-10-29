package com.blog.shiro;

import lombok.Data;
import com.blog.entity.User;

import java.io.Serializable;

/**
 * @author SZ-UserBDG7
 */
@Data
public class AccountProfile implements Serializable {
    
    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String avatar;

    private String email;

    public static AccountProfile from(User user) {
        AccountProfile profile = new AccountProfile();
        profile.setId(user.getId());
        profile.setUsername(user.getUsername());
        profile.setAvatar(user.getAvatar());
        profile.setEmail(user.getEmail());
        return profile;
    }
}
