package com.whc.fivechess.support;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 测试用 WebSocket 客户端：记录服务端下发的文本消息，支持按消息类型等待与计数。
 */
public class TestWsClient implements WebSocketHandler {

    private final List<String> received = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;
    private volatile WebSocketSession session;

public static TestWsClient connect(int port, String userId) throws Exception {
        TestWsClient client = new TestWsClient();
        client.session = new StandardWebSocketClient()
                .doHandshake(client, "ws://127.0.0.1:" + port + "/socket/" + userId)
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    /**
     * 建连后立即发送登录令牌鉴权消息（服务端要求首条消息必须是 auth）
     */
    public static TestWsClient connectAuthed(int port, String userId, String token) throws Exception {
        TestWsClient client = connect(port, userId);
        client.send("{\"type\":\"auth\",\"userId\":\"" + userId + "\",\"token\":\"" + token + "\"}");
        return client;
    }

    public void send(String json) throws Exception {
        session.sendMessage(new TextMessage(json));
    }

    public boolean isClosed() {
        return closed;
    }

    public List<String> messages() {
        return new ArrayList<>(received);
    }

    /** 已收到的某项类型消息条数 */
    public int count(String type) {
        int result = 0;
        for (String message : received) {
            if (message.contains("\"type\":\"" + type + "\"")) {
                result++;
            }
        }
        return result;
    }

    /** 某项类型消息的全部内容 */
    public List<String> ofType(String type) {
        List<String> result = new ArrayList<>();
        for (String message : received) {
            if (message.contains("\"type\":\"" + type + "\"")) {
                result.add(message);
            }
        }
        return result;
    }

    /** 等待某项类型消息出现，超时返回 null */
    public String await(String type, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String message = firstOfType(type);
            if (message != null) {
                return message;
            }
            Thread.sleep(20);
        }
        return null;
    }

    /** 等待某项类型消息累计到指定条数 */
    public boolean awaitCount(String type, int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (count(type) >= expected) {
                return true;
            }
            Thread.sleep(20);
        }
        return count(type) >= expected;
    }

    /** 等待连接被关闭 */
    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (closed) {
                return true;
            }
            Thread.sleep(20);
        }
        return closed;
    }

    public void close() {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception ignored) {
            //测试清理阶段忽略关闭异常
        }
    }

    private String firstOfType(String type) {
        for (String message : received) {
            if (message.contains("\"type\":\"" + type + "\"")) {
                return message;
            }
        }
        return null;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        //连接建立后无需额外处理
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        received.add(String.valueOf(message.getPayload()));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        //测试中连接异常交由 close 流程处理
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        closed = true;
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}