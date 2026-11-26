package com.whc.fivechess;

import cn.hutool.json.JSONArray;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 房间交互用例：准备状态、巡查消息完整性、管理员不可被踢、被踢用户再次加入的提示顺序。
 * 使用真实数据库，测试账号统一使用 ri 前缀，测试结束后清理测试数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoomInteractionTests {

    private static final long TIMEOUT = 4000L;
    private static final String PASSWORD = "ri123456";
    private static final String ROOM_PASSWORD = "room1234";
    private static final Random RANDOM = new Random();

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.whc.fivechess.component.ModerationService moderationService;

    private static final List<String> logins = new ArrayList<>();
    private static final Map<String, String> nameById = new HashMap<>();
    private static final Map<String, String> tokenById = new HashMap<>();
    private static final List<String> userIds = new ArrayList<>();

    private static String adminLogin;
    private static boolean createdAdmin;
    private static String adminId;

    private static String idA;
    private static String idB;
    private static String idC;
    private static String idD;
    private static String idE;

    private static TestWsClient adminClient;
    private static TestWsClient clientA;
    private static TestWsClient clientB;
    private static TestWsClient clientC;
    private static TestWsClient clientD;
    private static TestWsClient clientE;

    @BeforeAll
    void setUp() throws Exception {
        adminLogin = moderationService.getAdminLogin();
        createdAdmin = ensureAdminAccount(adminLogin);
        JSONObject adminContent = login(adminLogin).getJSONObject("content");
        adminId = String.valueOf(adminContent.getInt("id"));
        tokenById.put(adminId, adminContent.getStr("token"));
        userIds.add(adminId);

        idA = registerAndLogin("ria");
        idB = registerAndLogin("rib");
        idC = registerAndLogin("ric");
        idD = registerAndLogin("rid");
        idE = registerAndLogin("rie");

        adminClient = TestWsClient.connectAuthed(port, adminId, tokenById.get(adminId));
        clientA = TestWsClient.connectAuthed(port, idA, tokenById.get(idA));
        clientB = TestWsClient.connectAuthed(port, idB, tokenById.get(idB));
        clientC = TestWsClient.connectAuthed(port, idC, tokenById.get(idC));
        clientD = TestWsClient.connectAuthed(port, idD, tokenById.get(idD));
        clientE = TestWsClient.connectAuthed(port, idE, tokenById.get(idE));
        for (TestWsClient client : new TestWsClient[]{adminClient, clientA, clientB, clientC, clientD, clientE}) {
            assertNotNull(client.await("auth-success", TIMEOUT), "连接鉴权应成功");
        }
        String suffix = String.format("%04d", RANDOM.nextInt(10000));
        reportNick(adminClient, adminId, "巡查管理员" + suffix);
        reportNick(clientA, idA, "准备甲" + suffix);
        reportNick(clientB, idB, "准备乙" + suffix);
        reportNick(clientC, idC, "踢人丙" + suffix);
        reportNick(clientD, idD, "私房丁" + suffix);
        reportNick(clientE, idE, "私房戊" + suffix);
        Thread.sleep(300);
    }

    // ==================== 1. 准备状态可见 + 巡查消息包含系统事件 ====================

    @Test
    @Order(1)
    void readyStateVisibleAndAdminSeesSystemEvents() throws Exception {
        String roomId = createRoom(clientA, idA);
        joinRoom(clientB, idB, roomId, null);

        clientA.send("{\"type\":\"info-ready\",\"roomId\":\"" + roomId + "\",\"content\":true}");
        waitForReady(roomId, 1);

        adminClient.send("{\"type\":\"admin-enter-room\",\"roomId\":\"" + roomId + "\"}");
        String view = adminClient.await("admin-room-view", TIMEOUT);
        assertNotNull(view, "管理员应能进入房间巡查");
        JSONObject viewJson = JSONUtil.parseObj(view);
        JSONObject roomView = viewJson.getJSONObject("roomView");
        assertTrue(roomView.getBool("readyId1"), "巡查视图应包含房主已准备状态");
        assertFalse(roomView.getBool("readyId2"), "巡查视图应包含房客未准备状态");

        JSONArray events = viewJson.getJSONArray("events");
        assertNotNull(events, "巡查视图应附带房间系统事件");
        String allEvents = events.toString();
        assertTrue(allEvents.contains("已加入房间"), "系统事件应包含加入房间消息：" + allEvents);
        assertTrue(allEvents.contains("已准备"), "系统事件应包含准备消息：" + allEvents);

        //巡查期间的后续准备动作要实时写入巡查消息窗口
        clientB.send("{\"type\":\"info-ready\",\"roomId\":\"" + roomId + "\",\"content\":true}");
        String event = awaitTypeContaining(adminClient, "admin-room-event", "已准备", TIMEOUT);
        assertNotNull(event, "房客准备后管理员巡查消息应实时收到系统事件");
    }

    // ==================== 2. 管理员不能被踢出房间 ====================

    @Test
    @Order(2)
    void adminCannotBeKicked() throws Exception {
        String roomId = createRoom(clientC, idC);
        adminClient.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":{\"id\":\"" + adminId + "\",\"name\":\"管理员\"}}");
        assertNotNull(adminClient.await("join-room-success", TIMEOUT), "管理员应能作为普通玩家加入房间");

        clientC.send("{\"type\":\"kick-player\",\"targetId\":\"" + adminId + "\"}");
        String fail = clientC.await("kick-fail", TIMEOUT);
        assertNotNull(fail, "房主踢管理员应被拒绝");
        assertTrue(fail.contains("不能踢出管理员"), "拒绝原因应说明不能踢出管理员：" + fail);

        Room room = SocketServer.roomMap.get(roomId);
        assertNotNull(room);
        assertEquals(adminId, room.getPlayerId2(), "管理员应仍在房间内");

        adminClient.send("{\"type\":\"quit-room\"}");
        Thread.sleep(200);
    }

    // ==================== 3. 被踢用户再次加入：不校验密码，直接提示无法加入 ====================

    @Test
    @Order(3)
    void kickedUserRejoinSkipsPassword() throws Exception {
        String roomId = createRoom(clientD, idD);
        clientD.send("{\"type\":\"toggle-room-open\",\"open\":\"false\",\"password\":\"" + ROOM_PASSWORD + "\"}");
        assertNotNull(clientD.await("room-open-changed", TIMEOUT), "应能设为带密码的私人房间");

        joinRoom(clientE, idE, roomId, ROOM_PASSWORD);
        clientD.send("{\"type\":\"kick-player\",\"targetId\":\"" + idE + "\"}");
        assertNotNull(clientE.await("kicked-from-room", TIMEOUT), "被踢用户应收到通知");

        //被踢后用错误密码、甚至不带密码再加入，都应直接返回“已被移出”而不是密码错误
        clientE.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":{\"id\":\"" + idE + "\",\"name\":\"" + nameById.get(idE) + "\"}}");
        String fail = clientE.await("join-room-fail", TIMEOUT);
        assertNotNull(fail, "被踢用户再次加入应失败");
        assertTrue(fail.contains("你已被房主移出该房间，无法再次加入"), "应直接提示已被移出而不是要求密码：" + fail);
        assertFalse(fail.contains("密码"), "提示中不应包含密码校验：" + fail);

        //大厅房间视图应带上 kicked 标记，前端据此不弹密码框
        String rooms = awaitTypeContaining(clientE, "online-rooms", "\"kicked\":true", TIMEOUT);
        assertNotNull(rooms, "被踢用户收到的房间列表应标记该房间 kicked=true");
    }

    // ==================== 清理 ====================

    @AfterAll
    void cleanUp() {
        for (TestWsClient client : new TestWsClient[]{adminClient, clientA, clientB, clientC, clientD, clientE}) {
            if (client != null) {
                client.close();
            }
        }
        for (String id : userIds) {
            if (id == null) {
                continue;
            }
            try {
                Integer uid = Integer.valueOf(id);
                jdbc.update("delete from chat_public_record where player = ?", uid);
                jdbc.update("delete from chat_room_record where player = ?", uid);
                for (String dataId : jdbc.queryForList(
                        "select data from record where player1 = ? or player2 = ?", String.class, uid, uid)) {
                    jdbc.update("delete from game where id = ?", dataId);
                }
                jdbc.update("delete from record where player1 = ? or player2 = ?", uid, uid);
            } catch (Exception ignored) {
                //清理阶段忽略单条失败
            }
        }
        for (String login : logins) {
            jdbc.update("delete from moderation_log where target_login = ?", login);
            jdbc.update("delete from user where login = ?", login);
        }
        if (createdAdmin && adminLogin != null) {
            jdbc.update("delete from user where login = ?", adminLogin);
        }
    }

    // ==================== 辅助方法 ====================

    private void waitForReady(String roomId, int slot) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            Room room = SocketServer.roomMap.get(roomId);
            if (room != null && (slot == 1 ? room.isReadyId1() : room.isReadyId2())) {
                return;
            }
            Thread.sleep(20);
        }
        fail("准备状态未在服务端生效：" + roomId);
    }

    private boolean ensureAdminAccount(String login) {
        Integer count = jdbc.queryForObject("select count(*) from user where login = ?", Integer.class, login);
        if (count != null && count > 0) {
            return false;
        }
        jdbc.update("insert into user(login,name,password,time) values(?,?,?,?)",
                login, login, com.whc.fivechess.util.PasswordUtil.hash(PASSWORD),
                com.whc.fivechess.util.MyUtil.now());
        return true;
    }

    private String registerAndLogin(String prefix) {
        String login = prefix + String.format("%06d", RANDOM.nextInt(1000000));
        logins.add(login);
        post("/registerPost", "{\"id\":\"" + login + "\",\"name\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        JSONObject content = login(login).getJSONObject("content");
        String id = String.valueOf(content.getInt("id"));
        nameById.put(id, login);
        tokenById.put(id, content.getStr("token"));
        userIds.add(id);
        return id;
    }

    private JSONObject login(String login) {
        JSONObject resp = post("/loginPost", "{\"login\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        assertNotNull(resp.getJSONObject("content"), "登录应返回内容：" + resp);
        return resp;
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

    private void reportNick(TestWsClient client, String id, String nick) throws Exception {
        client.send("{\"type\":\"report-nick\",\"userId\":\"" + id + "\",\"nickName\":\"" + nick + "\"}");
    }

    private String createRoom(TestWsClient client, String id) throws Exception {
        int before = client.count("create-room-success");
        client.send("{\"type\":\"create-room\",\"user\":{\"id\":\"" + id + "\",\"name\":\"" + nameById.get(id) + "\"}}");
        assertTrue(client.awaitCount("create-room-success", before + 1, TIMEOUT), "创建房间应成功");
        return JSONUtil.parseObj(lastOfType(client, "create-room-success"))
                .getJSONObject("content").getStr("id");
    }

    private void joinRoom(TestWsClient client, String id, String roomId, String password) throws Exception {
        int before = client.count("join-room-success");
        String body = "{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":{\"id\":\"" + id
                + "\",\"name\":\"" + nameById.get(id) + "\"}}";
        if (password != null) {
            body = body.substring(0, body.length() - 1) + ",\"password\":\"" + password + "\"}";
        }
        client.send(body);
        assertTrue(client.awaitCount("join-room-success", before + 1, TIMEOUT), "加入房间应成功");
    }

    private String lastOfType(TestWsClient client, String type) {
        List<String> messages = client.ofType(type);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /** 等待某类型消息中出现指定片段 */
    private String awaitTypeContaining(TestWsClient client, String type, String keyword, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (String message : client.ofType(type)) {
                if (keyword == null || message.contains(keyword)) {
                    return message;
                }
            }
            Thread.sleep(20);
        }
        return null;
    }
}