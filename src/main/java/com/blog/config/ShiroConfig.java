package com.blog.config;

import com.blog.shiro.AccountRealm;
import com.blog.shiro.JwtFilter;
import com.blog.shiro.JedisRedisManager;
import org.apache.shiro.mgt.DefaultSubjectDAO;
import org.apache.shiro.mgt.DefaultSessionStorageEvaluator;
import org.apache.shiro.session.mgt.SessionManager;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.crazycake.shiro.RedisCacheManager;
import org.crazycake.shiro.RedisSessionDAO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import org.apache.shiro.mgt.SecurityManager;

import jakarta.servlet.Filter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * shiro启用注解拦截控制器
 * @author SZ-UserBDG7
 */
@Configuration
@Profile("!bootstrap-admin")
public class ShiroConfig {
    @Bean(destroyMethod = "close")
    public JedisRedisManager redisManager(
            @Value("${shiro-redis.redis-manager.host:127.0.0.1:6379}") String host,
            @Value("${shiro-redis.redis-manager.password:}") String password,
            @Value("${shiro-redis.redis-manager.database:0}") int database,
            @Value("${shiro-redis.redis-manager.timeout:2000}") int timeout) {
        return new JedisRedisManager(host, password, database, timeout);
    }

    @Bean
    public RedisSessionDAO redisSessionDAO(JedisRedisManager redisManager) {
        RedisSessionDAO dao = new RedisSessionDAO();
        dao.setRedisManager(redisManager);
        dao.setKeyPrefix("marker-hub:shiro:session:");
        return dao;
    }

    @Bean
    public RedisCacheManager redisCacheManager(JedisRedisManager redisManager) {
        RedisCacheManager cacheManager = new RedisCacheManager();
        cacheManager.setRedisManager(redisManager);
        cacheManager.setPrincipalIdFieldName("id");
        cacheManager.setKeyPrefix("marker-hub:shiro:cache:");
        return cacheManager;
    }

    @Bean
    public SessionManager sessionManager(RedisSessionDAO redisSessionDAO) {
        DefaultWebSessionManager sessionManager = new DefaultWebSessionManager();

        // inject redisSessionDAO
        sessionManager.setSessionDAO(redisSessionDAO);
        sessionManager.setSessionValidationSchedulerEnabled(false);
        sessionManager.setSessionIdCookieEnabled(false);
        sessionManager.setSessionIdUrlRewritingEnabled(false);
        return sessionManager;
    }

    @Bean
    public DefaultWebSecurityManager securityManager(AccountRealm accountRealm,
                                                     SessionManager sessionManager,
                                                     RedisCacheManager redisCacheManager) {

        DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager(accountRealm);

        //inject sessionManager
        securityManager.setSessionManager(sessionManager);

        // inject redisCacheManager
        securityManager.setCacheManager(redisCacheManager);
        securityManager.setRememberMeManager(null);
        DefaultSessionStorageEvaluator storage = new DefaultSessionStorageEvaluator();
        storage.setSessionStorageEnabled(false);
        DefaultSubjectDAO subjects = new DefaultSubjectDAO();
        subjects.setSessionStorageEvaluator(storage);
        securityManager.setSubjectDAO(subjects);
        return securityManager;
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chainDefinition = new DefaultShiroFilterChainDefinition();

        Map<String, String> filterMap = new LinkedHashMap<>();

        filterMap.put("/**", "jwt");
        chainDefinition.addPathDefinitions(filterMap);
        return chainDefinition;
    }

    @Bean("shiroFilterFactoryBean")
    public ShiroFilterFactoryBean shiroFilterFactoryBean(SecurityManager securityManager,
                                                         ShiroFilterChainDefinition shiroFilterChainDefinition,
                                                         @Value("${blog.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String origins) {
        ShiroFilterFactoryBean shiroFilter = new ShiroFilterFactoryBean();
        shiroFilter.setSecurityManager(securityManager);

        Map<String, Filter> filters = new HashMap<>();
        // Owned by Shiro only: a separate Spring Filter bean would also be registered by Boot.
        filters.put("jwt", new JwtFilter(origins));
        shiroFilter.setFilters(filters);

        Map<String, String> filterMap = shiroFilterChainDefinition.getFilterChainMap();

        shiroFilter.setFilterChainDefinitionMap(filterMap);
        return shiroFilter;
    }
}
