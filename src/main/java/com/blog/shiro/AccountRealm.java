package com.blog.shiro;

import com.blog.auth.AuthService;
import com.blog.common.ApiException;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!bootstrap-admin")
public class AccountRealm extends AuthorizingRealm {
    private final AuthService auth;

    public AccountRealm(AuthService auth) {
        this.auth = auth;
        setCachingEnabled(false);
        setAuthenticationCachingEnabled(false);
        setAuthorizationCachingEnabled(false);
    }

    @Override
    public boolean supports(AuthenticationToken token) { return token instanceof JwtToken; }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) { return null; }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        try {
            AccountProfile profile = auth.authenticate((String) token.getCredentials());
            return new SimpleAuthenticationInfo(profile, token.getCredentials(), getName());
        } catch (ApiException expected) {
            throw new AuthenticationException(expected.getMessage(), expected);
        }
    }
}
