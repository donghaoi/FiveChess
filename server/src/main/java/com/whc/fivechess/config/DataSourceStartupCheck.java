package com.whc.fivechess.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 启动时校验数据库配置：口令必须由外部注入，并且能真正连上数据库。
 * 配置文件中不再保存明文口令，因此这里做一次显式检查，
 * 避免口令缺失时服务"看起来启动成功"、调用接口才报错。
 */
@Component
public class DataSourceStartupCheck {

    @Value("${spring.datasource.password:}")
    private String password;

    @Autowired
    private DataSource dataSource;

    @PostConstruct
    public void check() {
        if (password == null || password.trim().isEmpty() || password.contains("${")) {
            throw new IllegalStateException(
                    "数据库口令未配置：请先设置环境变量 DB_PASSWORD（需要时再设置 DB_USERNAME、DB_URL）后再启动服务。");
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(3)) {
                throw new IllegalStateException("数据库连接不可用，请检查 DB_URL、DB_USERNAME、DB_PASSWORD 及数据库状态。");
            }
            System.out.println("数据库连接检查通过：" + connection.getMetaData().getURL());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "数据库连接失败，请检查 DB_URL、DB_USERNAME、DB_PASSWORD 及数据库状态：" + e.getMessage(), e);
        }
    }
}