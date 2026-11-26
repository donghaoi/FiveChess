package com.whc.fivechess.security;

import javax.servlet.http.HttpServletRequest;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import java.lang.reflect.Field;
import java.util.List;

/**
 * WebSocket 握手配置器：在握手阶段解析客户端 IP 并写入会话用户属性，
 * 供 {@code onOpen} 做单 IP 连接数限制。
 * Tomcat 下通过反射取到底层 HttpServletRequest；取不到时退化为从握手头读取代理地址，
 * 两者都失败也不阻断连接（仅该连接不计入单 IP 限制）。
 */
public class FsServerConfigurator extends ServerEndpointConfig.Configurator {

    /** 会话用户属性中存放客户端 IP 的键 */
    public static final String CLIENT_IP = "fcClientIp";

    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
        try {
            //ClientIpResolver.resolve 内部已按配置决定是否读 CF-Connecting-IP / X-Forwarded-For
            String ip = resolveFromServletRequest(request);
            //只有显式信任代理时才读取转发头，否则直连客户端可伪造 X-Forwarded-For 绕过单 IP 连接数限制
            if (ip == null) {
                ip = resolveFromHeaders(request);
            }
            if (ip != null && !ip.isEmpty() && !ClientIpResolver.UNKNOWN.equals(ip)) {
                config.getUserProperties().put(CLIENT_IP, ip);
            }
        } catch (Exception e) {
            // 取不到客户端 IP 不影响连接建立，仅该连接不参与单 IP 连接数限制
        }
    }

    /** Tomcat 的 WsHandshakeRequest 内部持有 HttpServletRequest，反射取出后即可拿到远端地址 */
    private String resolveFromServletRequest(HandshakeRequest request) {
        if (request == null) {
            return null;
        }
        try {
            Field field = request.getClass().getDeclaredField("request");
            field.setAccessible(true);
            Object value = field.get(request);
            if (value instanceof HttpServletRequest) {
                return ClientIpResolver.resolve((HttpServletRequest) value);
            }
        } catch (Exception ignored) {
            // 非 Tomcat 实现或字段变更时走头部兜底
        }
        return null;
    }

    private String resolveFromHeaders(HandshakeRequest request) {
        if (request == null || request.getHeaders() == null) {
            return null;
        }
        //走到这里说明反射取不到 HttpServletRequest：改用握手头，规则与 HTTP 链路一致。
        //CDN / Render 边缘场景优先 CF-Connecting-IP（边缘覆盖写，客户端伪造无效）。
        if (ClientIpResolver.isTrustCloudflareHeader()) {
            String edge = ClientIpResolver.fromCloudflareHeader(
                    firstHeader(request, ClientIpResolver.HEADER_CF_CONNECTING_IP));
            if (edge != null) {
                return edge;
            }
        }
        if (!ClientIpResolver.isTrustForwardedFor()) {
            return null;
        }
        //按可信代理层数取右起第 N 跳，并校验必须是合法 IP 字面量
        String xff = firstHeader(request, "x-forwarded-for");
        String real = firstHeader(request, "x-real-ip");
        return ClientIpResolver.fromForwarded(xff, real);
    }

    private String firstHeader(HandshakeRequest request, String name) {
        List<String> values = request.getHeaders().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}