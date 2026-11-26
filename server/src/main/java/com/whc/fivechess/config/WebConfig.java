package com.whc.fivechess.config;

import com.whc.fivechess.security.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 允许跨域访问的来源白名单，配置项：fivechess.cors.allowed-origin-patterns
     * 支持 http://localhost:* 这类通配写法；null 用于以 file:// 直接打开页面的场景。
     * 生产环境请改为自己的域名，并去掉 null。
     */
    @Value("${fivechess.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*,https://localhost:*,https://127.0.0.1:*}")
    private List<String> allowedOriginPatterns;

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //所有 HTTP 接口按 IP 做总速率限制，防止爬虫与恶意刷接口；
        //存活探针由外部监控定期 GET 调用，排除限流，避免 429 被误判成服务宕机；
        //登录态探测在页面打开、切回前台、断线重连时都会调用，同样排除限流
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
				// 不需要存活检测时隐藏下面这一行
                .excludePathPatterns("/health", "/sessionCheck");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        //不再使用 allowedOrigins("*")，只放行白名单中的来源
        registry.addMapping("/**")
                .allowedOriginPatterns(expandDefaultPorts(allowedOriginPatterns).toArray(new String[0]))
                //登录态通过请求体中的令牌传递，不使用 Cookie
                .allowCredentials(false)
                //接口以 POST 为主，GET 用于 /avatar/{id} 等图片类只读资源；放开 OPTIONS 以支持跨域预检
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * 给「通配端口」的 pattern 补一条不带端口的等价形式。
     *
     * <p>浏览器发 Origin 时对默认端口（http 80 / https 443）<b>不写端口号</b>，
     * 例如页面在 https://example.com 时 Origin 就是 {@code https://example.com}，而不是
     * {@code https://example.com:443}。而 Spring 的 origin pattern 是按「scheme + host + port」
     * 逐段比对的：{@code https://example.com:*} 的端口段是 "*"，能匹配 :443、:8080，
     * 却匹配不上「根本没有端口段」的写法，结果白名单域名照样 403。</p>
     *
     * <p>所以凡是配置了 {@code scheme://host:*}，都同时接受 {@code scheme://host}。</p>
     */
    private List<String> expandDefaultPorts(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return patterns;
        }
        Set<String> expanded = new LinkedHashSet<>();
        for (String raw : patterns) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String pattern = raw.trim();
            expanded.add(pattern);
            if (pattern.endsWith(":*")) {
                expanded.add(pattern.substring(0, pattern.length() - 2));
            }
        }
        return new ArrayList<>(expanded);
    }


}