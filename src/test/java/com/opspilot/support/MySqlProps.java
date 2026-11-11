package com.opspilot.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 集成测试连接的数据库，由环境变量指定，例如：
 * <pre>
 * docker run --rm --network opspilot_default -e IT_MYSQL_URL=jdbc:mysql://mysql:3306/opspilot ... mvn test
 * </pre>
 * 测试只增删自己造的数据（随机 PO 号 / 随机 key），不会清空库里已有的数据。
 */
public final class MySqlProps {

    private MySqlProps() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        String url = System.getenv("IT_MYSQL_URL");
        String separator = url.contains("?") ? "&" : "?";
        registry.add("spring.datasource.url", () -> url + separator
                + "useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("IT_MYSQL_USER", "opspilot"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("IT_MYSQL_PASSWORD", "opspilot123"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
    }
}
