package com.whc.fivechess.websocket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
public class WebSocketConfig
{
   // web socket的配置
    @Bean
    public ServerEndpointExporter serverEndpointExporter(){
        return new ServerEndpointExporter();
    }

    /**
     * 提高 WebSocket 文本/二进制消息缓冲区上限。
     * 默认文本缓冲区只有 8KB，稍长的消息会导致连接被容器断开，
     * 这里放宽到 64KB，聊天内容长度由业务层再做限制。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer(){
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        return container;
    }
}