package com.blog.config;

import com.blog.shiro.AccountRealm;
import com.blog.shiro.JwtFilter;
import com.blog.shiro.JedisRedisManager;
import com.blog.utils.JwtUtil;
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
        return dao;
    }

    @Bean
    public RedisCacheManager redisCacheManager(JedisRedisManager redisManager) {
        RedisCacheManager cacheManager = new RedisCacheManager();
        cacheManager.setRedisManager(redisManager);
        cacheManager.setPrincipalIdFieldName("id");
        return cacheManager;
    }

    @Bean
    public SessionManager sessionManager(RedisSessionDAO redisSessionDAO) {
        DefaultWebSessionManager sessionManager = new DefaultWebSessionManager();

        // inject redisSessionDAO
        sessionManager.setSessionDAO(redisSessionDAO);
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
                                                         JwtUtil jwtUtil) {
        ShiroFilterFactoryBean shiroFilter = new ShiroFilterFactoryBean();
        shiroFilter.setSecurityManager(securityManager);

        Map<String, Filter> filters = new HashMap<>();
        // Owned by Shiro only: a separate Spring Filter bean would also be registered by Boot.
        filters.put("jwt", new JwtFilter(jwtUtil));
        shiroFilter.setFilters(filters);

        Map<String, String> filterMap = shiroFilterChainDefinition.getFilterChainMap();

        shiroFilter.setFilterChainDefinitionMap(filterMap);
        return shiroFilter;
    }
}
