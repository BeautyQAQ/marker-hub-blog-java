package com.blog.controller;

import com.blog.common.ApiException;
import com.blog.common.Result;
import com.blog.shiro.AccountProfile;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
@Profile("!bootstrap-admin")
public class UserController {
    @GetMapping("/{id}")
    @RequiresAuthentication
    public Result profile(@PathVariable Long id) {
        AccountProfile profile = (AccountProfile) SecurityUtils.getSubject().getPrincipal();
        if (!profile.getId().equals(id)) throw new ApiException(HttpStatus.FORBIDDEN, "只能访问自己的账号信息");
        return Result.successful("获取成功", profile);
    }
}
