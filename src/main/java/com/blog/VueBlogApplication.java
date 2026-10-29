package com.blog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

/**
 * @author SZ-UserBDG7
 */
@SpringBootApplication
public class VueBlogApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(VueBlogApplication.class,args);
        if (context.getEnvironment().acceptsProfiles(Profiles.of("bootstrap-admin"))) {
            context.close();
        }
    }
}
