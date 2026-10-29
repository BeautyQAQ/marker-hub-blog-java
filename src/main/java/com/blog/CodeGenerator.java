package com.blog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.DateType;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;
import org.apache.ibatis.type.JdbcType;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

/** Generates sources on explicit invocation; never used as a connectivity check. */
public class CodeGenerator {
    public static void main(String[] args) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        if (properties == null) {
            throw new IllegalStateException("无法读取 application.yml 数据源配置");
        }
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new PropertiesPropertySource("application.yml", properties));

        System.out.println("请输入表名，多个表名用英文逗号分隔：");
        Scanner scanner = new Scanner(System.in);
        if (!scanner.hasNextLine()) {
            throw new IllegalArgumentException("请输入正确的表名");
        }
        String tables = scanner.nextLine().trim();
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("请输入正确的表名");
        }
        Path projectPath = Path.of(System.getProperty("user.dir"));
        FastAutoGenerator.create(
                        environment.getRequiredProperty("spring.datasource.url"),
                        environment.getRequiredProperty("spring.datasource.username"),
                        environment.getRequiredProperty("spring.datasource.password"))
                .globalConfig(builder -> builder.author("huangshen")
                        .disableOpenDir().dateType(DateType.ONLY_DATE)
                        .outputDir(projectPath.resolve("src/main/java").toString()))
                .dataSourceConfig(builder -> builder.typeConvertHandler((globalConfig, registry, metaInfo) ->
                        metaInfo.getJdbcType() == JdbcType.TINYINT
                                ? DbColumnType.INTEGER : registry.getColumnType(metaInfo)))
                .packageConfig(builder -> builder.parent("com.blog")
                        .pathInfo(Map.of(OutputFile.xml, projectPath.resolve("src/main/resources/mapper").toString())))
                .strategyConfig(builder -> builder.addInclude(tables.split("\\s*,\\s*"))
                        .addTablePrefix("m_")
                        .entityBuilder().enableLombok().enableChainModel().idType(IdType.AUTO)
                        .serviceBuilder().formatServiceFileName("%sService")
                        .controllerBuilder().enableRestStyle().enableHyphenStyle())
                .templateEngine(new FreemarkerTemplateEngine())
                .execute();
    }
}
