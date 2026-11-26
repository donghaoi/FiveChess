package com.whc.fivechess;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.support.TestWsClient;
import com.whc.fivechess.websocket.component.SocketServer;
import com.whc.fivechess.websocket.domain.Room;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 稳定性测试：连接反复建立/断开、并发建房退房、单房间长时间连续落子、
 * 并发公共聊天、异常报文（超出缓冲区上限）下服务可用性与连接集合是否泄漏。
 * 使用真实数据库，测试账号统一使用 st 前缀，结束后清理测试数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StabilityTests {

    //连接反复建立/断开的轮次
    private static final int CONNECT_ROUNDS = 120;
    //并发建房退房的线程数与每线程轮次
    private static final int CONCURRENT_THREADS = 8;
    private static final int CONCURRENT_ROUNDS = 15;
    //单房间连续落子步数（不超过棋盘 18*18，按无五连的着色顺序落子）
    private static final int MOVE_ROUNDS = 150;
    //并发公共聊天：人数与每人消息数
    private static final int CHAT_CLIENTS = 8;
    private static final int CHAT_MESSAGES = 20;
    private static final long TIMEOUT = 8000L;

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Random RANDOM = new Random();
    private final List<String> logins = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> tokenById = new ConcurrentHashMap<>();
    private final List<TestWsClient> opened = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void setUp() {
        //注册一批稳定性测试账号，供各用例复用
        for (int i = 0; i < CONCURRENT_THREADS + 2; i++) {
            registerAndLogin("st" + i);
        }
    }

    @Test
    @Order(1)
    void repeatedConnectAndDisconnect() throws Exception {
        int sessionsBefore = SocketServer.sessionMap.size();
        int nickBefore = SocketServer.onlineUserNickMap.size();
        String id = idAt(0);
        String token = tokenById.get(id);
        for (int i = 0; i < CONNECT_ROUNDS; i++) {
            TestWsClient client = TestWsClient.connectAuthed(port, id, token);
            client.send("{\"type\":\"report-nick\",\"userId\":\"" + id + "\",\"nickName\":\"压测用户\"}");
            client.close();
        }
        assertTrue(awaitGone(Collections.singletonList(id), 20000), "断开后不应残留连接/在线昵称/房间关联");
        assertTrue(SocketServer.sessionMap.size() <= sessionsBefore, "反复连接不应导致 session 泄漏");
        assertTrue(SocketServer.onlineUserNickMap.size() <= nickBefore, "反复连接不应导致在线昵称泄漏");
        System.out.println("[稳定性] 连接反复建立/断开 " + CONNECT_ROUNDS + " 次完成，sessionMap="
                + SocketServer.sessionMap.size() + "，onlineUserNickMap=" + SocketServer.onlineUserNickMap.size());
    }

    @Test
    @Order(2)
    void concurrentCreateAndQuitRooms() throws Exception {
        int roomsBefore = SocketServer.roomMap.size();
        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < CONCURRENT_THREADS; t++) {
            final String id = idAt(t);
            final String token = tokenById.get(id);
            ids.add(id);
            futures.add(pool.submit(() -> {
                for (int r = 0; r < CONCURRENT_ROUNDS; r++) {
                    try {
                        TestWsClient client = TestWsClient.connectAuthed(port, id, token);
                        client.send("{\"type\":\"create-room\",\"user\":{\"id\":\"" + id + "\",\"name\":\"" + id + "\"}}");
                        if (client.await("create-room-success", TIMEOUT) == null) {
                            errors.add("创建房间未收到成功响应：" + id);
                        }
                        client.send("{\"type\":\"quit-room\",\"userId\":\"" + id + "\"}");
                        Thread.sleep(5);
                        client.close();
                    } catch (Throwable e) {
                        errors.add(id + " -> " + e);
                    }
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(180, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(0, errors.size(), "并发建房退房过程中出现异常：" + errors);
        assertTrue(awaitGone(ids, 20000), "并发结束后不应残留房间归属");
        assertTrue(SocketServer.roomMap.size() <= roomsBefore, "并发建房退房不应导致房间泄漏");
        System.out.println("[稳定性] " + CONCURRENT_THREADS + " 线程各建房退房 " + CONCURRENT_ROUNDS
                + " 次完成，roomMap=" + SocketServer.roomMap.size());
    }

    @Test
    @Order(3)
    void longGameInOneRoom() throws Exception {
        String id1 = idAt(CONCURRENT_THREADS);
        String id2 = idAt(CONCURRENT_THREADS + 1);
        TestWsClient a = TestWsClient.connectAuthed(port, id1, tokenById.get(id1));
        TestWsClient b = TestWsClient.connectAuthed(port, id2, tokenById.get(id2));
        opened.add(a);
        opened.add(b);
        a.send("{\"type\":\"report-nick\",\"userId\":\"" + id1 + "\",\"nickName\":\"压测甲\"}");
        b.send("{\"type\":\"report-nick\",\"userId\":\"" + id2 + "\",\"nickName\":\"压测乙\"}");

        int beforeCreate = a.count("create-room-success");
        a.send("{\"type\":\"create-room\",\"user\":{\"id\":\"" + id1 + "\",\"name\":\"压测甲\"}}");
        assertTrue(a.awaitCount("create-room-success", beforeCreate + 1, TIMEOUT), "创建房间应成功");
        String roomId = JSONUtil.parseObj(lastOfType(a, "create-room-success"))
                .getJSONObject("content").getStr("id");
        int beforeJoin = b.count("join-room-success");
        b.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":{\"id\":\"" + id2 + "\",\"name\":\"压测乙\"}}");
        assertTrue(b.awaitCount("join-room-success", beforeJoin + 1, TIMEOUT), "加入房间应成功");
        // 服务端要求双方都准备后才能开局
        a.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + id1 + "\",\"to\":\"" + id2 + "\"}");
        b.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + id2 + "\",\"to\":\"" + id1 + "\"}");
        Thread.sleep(150);
        a.send("{\"type\":\"start-game\",\"roomId\":\"" + roomId + "\"}");
        long start = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < start
                && (SocketServer.roomMap.get(roomId) == null || SocketServer.roomMap.get(roomId).getColor1() == null)) {
            Thread.sleep(20);
        }
        Room room = SocketServer.roomMap.get(roomId);
        assertNotNull(room, "房间应存在");
        assertNotNull(room.getColor1(), "开局后应由服务端记录棋子颜色");
        String blackId = "black".equals(room.getColor1()) ? id1 : id2;
        String whiteId = "black".equals(room.getColor1()) ? id2 : id1;
        TestWsClient black = blackId.equals(id1) ? a : b;
        TestWsClient white = whiteId.equals(id1) ? a : b;

        //按 ((x + 2y) % 4 < 2) 着色落子，任意方向都不会连成五子
        List<int[]> blackCells = new ArrayList<>();
        List<int[]> whiteCells = new ArrayList<>();
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 18; x++) {
                if (((x + 2 * y) % 4) < 2) {
                    if (blackCells.size() < MOVE_ROUNDS) {
                        blackCells.add(new int[]{x, y});
                    }
                } else if (whiteCells.size() < MOVE_ROUNDS) {
                    whiteCells.add(new int[]{x, y});
                }
            }
        }
        assertEquals(MOVE_ROUNDS, blackCells.size());
        assertEquals(MOVE_ROUNDS, whiteCells.size());

        int expected = 0;
        for (int i = 0; i < MOVE_ROUNDS; i++) {
            sendMove(black, roomId, blackCells.get(i)[0], blackCells.get(i)[1], "black", blackId, whiteId, ++expected);
            sendMove(white, roomId, whiteCells.get(i)[0], whiteCells.get(i)[1], "white", whiteId, blackId, ++expected);
        }
        assertEquals(MOVE_ROUNDS * 2, SocketServer.roomMap.get(roomId).getSteps().size(),
                "连续落子不应丢失或重复记录步数");

        a.send("{\"type\":\"quit-room\",\"userId\":\"" + id1 + "\"}");
        b.send("{\"type\":\"quit-room\",\"userId\":\"" + id2 + "\"}");
        long quitDeadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < quitDeadline && SocketServer.roomMap.containsKey(roomId)) {
            Thread.sleep(100);
        }
        assertNull(SocketServer.roomMap.get(roomId), "全部离开后房间应被回收");
        a.close();
        b.close();
        System.out.println("[稳定性] 单房间连续落子 " + (MOVE_ROUNDS * 2) + " 步全部记录，房间已回收");
    }

    @Test
    @Order(4)
    void concurrentPublicChat() throws Exception {
        List<TestWsClient> clients = new ArrayList<>();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < CHAT_CLIENTS; i++) {
            String id = idAt(i);
            TestWsClient client = TestWsClient.connectAuthed(port, id, tokenById.get(id));
            opened.add(client);
            assertNotNull(client.await("auth-success", TIMEOUT), "鉴权应成功");
            client.send("{\"type\":\"report-nick\",\"userId\":\"" + id + "\",\"nickName\":\"聊天压测" + i + "\"}");
            clients.add(client);
        }
        //全部连接并上报昵称后再并发发送，保证每条消息都广播到所有连接
        Thread.sleep(500);
        ExecutorService pool = Executors.newFixedThreadPool(CHAT_CLIENTS);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < CHAT_CLIENTS; i++) {
            final TestWsClient client = clients.get(i);
            futures.add(pool.submit(() -> {
                for (int m = 0; m < CHAT_MESSAGES; m++) {
                    try {
                        client.send("{\"type\":\"public-chat-message\",\"content\":\"并发消息" + m + "\"}");
                    } catch (Throwable e) {
                        errors.add("发送失败：" + e);
                    }
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(0, errors.size(), "并发发送公共消息不应报错：" + errors);

        int total = CHAT_CLIENTS * CHAT_MESSAGES;
        TestWsClient observer = clients.get(0);
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline && observer.count("public-chat-message") < total) {
            Thread.sleep(100);
        }
        assertEquals(total, observer.count("public-chat-message"),
                "并发公共聊天不应丢失消息（每条消息都会广播给全部在线连接）");
        for (TestWsClient client : clients) {
            client.close();
        }
        System.out.println("[稳定性] " + CHAT_CLIENTS + " 个连接并发发送 " + CHAT_MESSAGES
                + " 条公共消息，共 " + total + " 条全部广播成功");
    }

    @Test
    @Order(5)
    void oversizedMessageKeepsServerAlive() throws Exception {
        String bigId = idAt(0);
        TestWsClient big = TestWsClient.connectAuthed(port, bigId, tokenById.get(bigId));
        opened.add(big);
        StringBuilder payload = new StringBuilder("{\"type\":\"public-chat-message\",\"content\":\"");
        for (int i = 0; i < 100 * 1024; i++) {
            payload.append('x');
        }
        payload.append("\"}");
        try {
            big.send(payload.toString());
        } catch (Exception ignored) {
            //发送失败是预期结果之一：超出容器缓冲区上限时连接会被断开
        }
        //服务端必须保持可用：新连接仍能正常鉴权
        String id = idAt(CONCURRENT_THREADS + 1);
        TestWsClient alive = TestWsClient.connectAuthed(port, id, tokenById.get(id));
        opened.add(alive);
        assertNotNull(alive.await("auth-success", TIMEOUT), "异常报文后服务端仍应正常响应其他连接");
        alive.close();

        //超限连接最终被容器清理，不残留 session
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && SocketServer.sessionMap.containsKey(bigId)) {
            Thread.sleep(100);
        }
        assertFalse(SocketServer.sessionMap.containsKey(bigId), "超限连接应被清理，避免连接对象泄漏");
        big.close();
        System.out.println("[稳定性] 超出缓冲区上限的报文未影响服务可用性，异常连接已被清理");
    }

    @AfterAll
    void cleanUp() {
        for (TestWsClient client : opened) {
            client.close();
        }
        opened.clear();
        for (String login : logins) {
            jdbc.update("delete from record where name1 = ? or name2 = ?", login, login);
            jdbc.update("delete from user where login = ?", login);
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from user where login like 'st%'", Integer.class),
                "稳定性测试账号应已清理");
    }

    private String registerAndLogin(String prefix) {
        String login = prefix + String.format("%06d", RANDOM.nextInt(1000000));
        logins.add(login);
        post("/registerPost", "{\"id\":\"" + login + "\",\"name\":\"" + login + "\",\"password\":\"st123456\"}");
        JSONObject loginResponse = post("/loginPost", "{\"login\":\"" + login + "\",\"password\":\"st123456\"}");
        JSONObject content = loginResponse.getJSONObject("content");
        String id = String.valueOf(content.getInt("id"));
        tokenById.put(id, content.getStr("token"));
        return id;
    }

    private JSONObject post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        JSONObject json = JSONUtil.parseObj(response.getBody());
        assertEquals(200, json.getInt("code"), "请求 " + path + " 失败：" + response.getBody());
        return json;
    }

    /** 按下标取测试账号的 id（logins 为注册顺序） */
    private String idAt(int index) {
        String login = logins.get(index);
        return jdbc.queryForObject("select id from user where login = ?", String.class, login);
    }

    private String lastOfType(TestWsClient client, String type) {
        List<String> messages = client.ofType(type);
        assertFalse(messages.isEmpty(), "应已收到类型为 " + type + " 的消息");
        return messages.get(messages.size() - 1);
    }

    /**
     * 发送一步落子并等待服务端记录，保证双方落子的先后顺序
     */
    private void sendMove(TestWsClient mover, String roomId, int x, int y, String color,
                          String from, String to, int expectedSteps) throws Exception {
        assertTrue(x < 18 && y < 18, "测试坐标应在棋盘内：" + x + "," + y);
        mover.send("{\"type\":\"play\",\"x\":" + x + ",\"y\":" + y + ",\"color\":\"" + color + "\","
                + "\"roomId\":\"" + roomId + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}");
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            Room room = SocketServer.roomMap.get(roomId);
            if (room == null || room.getSteps() == null || room.getSteps().size() >= expectedSteps) {
                return;
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("落子未被服务端记录：期望步数 " + expectedSteps);
    }

    /** 等待给定 id 全部从连接相关集合中消失 */
    private boolean awaitGone(List<String> ids, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (allGone(ids)) {
                return true;
            }
            Thread.sleep(100);
        }
        return allGone(ids);
    }

    private boolean allGone(List<String> ids) {
        for (String id : ids) {
            if (SocketServer.sessionMap.containsKey(id)
                    || SocketServer.onlineUserNickMap.containsKey(id)
                    || SocketServer.playerRoomMap.containsKey(id)) {
                return false;
            }
        }
        return true;
    }
}