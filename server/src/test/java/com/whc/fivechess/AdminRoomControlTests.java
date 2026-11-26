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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理员巡查与房内封号联动用例：
 * 1) 管理员巡查时能看到已加入的对手（不停留在「等待加入」），发言使用管理员昵称；
 * 2) 封号房客：终止对局并保留房主的等待位置；
 * 3) 封号房主：剩余房客继承房主并回到等待状态；
 * 4) 房间仅剩一人被封号：解散房间并让正在巡查的管理员回到大厅。
 * 使用真实数据库，测试账号统一使用 arc 前缀，测试结束后清理测试数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminRoomControlTests {

    private static final long TIMEOUT = 4000L;
    private static final String PASSWORD = "arc123456";
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
    private static final Map<String, String> nickById = new HashMap<>();
    private static final List<String> userIds = new ArrayList<>();

    private static String adminLogin;
    private static boolean createdAdmin;
    private static String adminId;
    private static String adminNick;
    /** 管理员在数据库中的注册昵称：服务端以此作为权威展示昵称 */
    private static String adminDisplayName;

    private static String id1;
    private static String id2;
    private static String id3;
    private static String id4;
    private static String id5;

    private static TestWsClient adminClient;
    private static TestWsClient client1;
    private static TestWsClient client2;
    private static TestWsClient client3;
    private static TestWsClient client4;
    private static TestWsClient client5;

    private static String room1;
    private static String room2;
    private static String room3;

    @BeforeAll
    void setUp() throws Exception {
        adminLogin = moderationService.getAdminLogin();
        createdAdmin = ensureAdminAccount(adminLogin);
        JSONObject adminContent = login(adminLogin).getJSONObject("content");
        adminId = String.valueOf(adminContent.getInt("id"));
        tokenById.put(adminId, adminContent.getStr("token"));
        assertTrue(adminContent.getBool("admin"), "登录响应应标记管理员身份");
        adminDisplayName = jdbc.queryForObject("select name from user where login = ?", String.class, adminLogin);
        userIds.add(adminId);

        //一次性准备全部测试账号：封号会把来源 IP 标记为封禁，之后再注册会被拒绝
        id1 = registerAndLogin("arca");
        id2 = registerAndLogin("arcb");
        id3 = registerAndLogin("arcc");
        id4 = registerAndLogin("arcd");
        id5 = registerAndLogin("arce");

        adminClient = TestWsClient.connectAuthed(port, adminId, tokenById.get(adminId));
        client1 = TestWsClient.connectAuthed(port, id1, tokenById.get(id1));
        client2 = TestWsClient.connectAuthed(port, id2, tokenById.get(id2));
        client3 = TestWsClient.connectAuthed(port, id3, tokenById.get(id3));
        client4 = TestWsClient.connectAuthed(port, id4, tokenById.get(id4));
        client5 = TestWsClient.connectAuthed(port, id5, tokenById.get(id5));
        assertNotNull(adminClient.await("auth-success", TIMEOUT), "管理员应鉴权成功");
        assertNotNull(client1.await("auth-success", TIMEOUT));
        assertNotNull(client2.await("auth-success", TIMEOUT));
        assertNotNull(client3.await("auth-success", TIMEOUT));
        assertNotNull(client4.await("auth-success", TIMEOUT));
        assertNotNull(client5.await("auth-success", TIMEOUT));

        String suffix = String.format("%04d", RANDOM.nextInt(10000));
        adminNick = "巡查管理员" + suffix;
        nickById.put(adminId, adminNick);
        nickById.put(id1, "巡查甲" + suffix);
        nickById.put(id2, "巡查乙" + suffix);
        nickById.put(id3, "巡查丙" + suffix);
        nickById.put(id4, "巡查丁" + suffix);
        nickById.put(id5, "巡查戊" + suffix);
        reportNick(adminClient, adminId, adminNick);
        reportNick(client1, id1, nickById.get(id1));
        reportNick(client2, id2, nickById.get(id2));
        reportNick(client3, id3, nickById.get(id3));
        reportNick(client4, id4, nickById.get(id4));
        reportNick(client5, id5, nickById.get(id5));
        Thread.sleep(300);
    }

    // ==================== 1. 巡查视图实时反映已加入的对手，发言用管理员昵称 ====================

    @Test
    @Order(1)
    void adminObserverSeesGuestAndUsesAdminNick() throws Exception {
        room1 = createRoom(client1, id1);
        joinRoom(client2, id2, room1);

        adminClient.send("{\"type\":\"admin-enter-room\",\"roomId\":\"" + room1 + "\"}");
        String view = adminClient.await("admin-room-view", TIMEOUT);
        assertNotNull(view, "管理员应能进入房间巡查");
        JSONObject viewJson = JSONUtil.parseObj(view);
        JSONObject roomView = viewJson.getJSONObject("roomView");
        assertEquals(room1, viewJson.getStr("roomId"));
        assertNotNull(roomView);
        assertNotNull(adminDisplayName, "管理员账号应存在注册昵称");
        assertEquals(nameById.get(id2), roomView.getStr("playerName2"),
                "巡查视图应直接显示已加入的对手，而不是「等待加入」");
        assertEquals(id2, roomView.getStr("playerId2"));
        assertFalse("waiting".equals(roomView.getStr("state")), "有对手加入后房间状态不应还是等待加入");

        //管理员在房内发言：房内用户看到的昵称应为管理员昵称（不能是「匿名玩家」）
        adminClient.send("{\"type\":\"chat-message\",\"roomId\":\"" + room1 + "\",\"content\":\"管理员巡查发言\"}");
        String chat = awaitChatContaining(client1, "管理员巡查发言", TIMEOUT);
        assertNotNull(chat, "房内用户应收到管理员发出的房间消息");
        JSONObject chatJson = JSONUtil.parseObj(chat);
        assertEquals(adminDisplayName, chatJson.getStr("fromName"), "管理员发言应显示管理员昵称");
        assertFalse("匿名玩家".equals(chatJson.getStr("fromName")), "管理员发言不应显示为匿名玩家");
        assertFalse("匿名用户".equals(chatJson.getStr("fromName")), "管理员发言不应显示为匿名用户");
    }

    // ==================== 2. 封号房客：终止对局并保留等待位置 ====================

    @Test
    @Order(2)
    void banGuestEndsGameAndKeepsWaitingSlot() throws Exception {
        //双方准备后服务端自动开局，验证「游戏中被封号」的场景
        client1.send("{\"type\":\"info-ready\",\"roomId\":\"" + room1 + "\",\"content\":true}");
        client2.send("{\"type\":\"info-ready\",\"roomId\":\"" + room1 + "\",\"content\":true}");
        assertNotNull(client1.await("start-game", TIMEOUT), "双方准备后应能开局");
        assertNotNull(client2.await("start-game", TIMEOUT), "双方准备后应能开局");
        Room started = SocketServer.roomMap.get(room1);
        assertNotNull(started);
        assertNotNull(started.getSteps(), "开局后应有棋局数据");

        adminClient.send("{\"type\":\"admin-ban\",\"targetId\":\"" + id2 + "\"}");
        assertNotNull(client2.await("banned", TIMEOUT), "被封号用户应收到通知");

        //房主收到对手离开的通知，房间回到等待加入状态，棋局数据被清空
        assertNotNull(client1.await("client-leave", TIMEOUT), "房主应收到对手离开房间的通知");
        Room room = SocketServer.roomMap.get(room1);
        assertNotNull(room, "只剩房主时房间应保留");
        assertEquals("-1", room.getPlayerId2(), "被封号用户应被移出房间");
        assertEquals("", room.getPlayerName2());
        assertEquals("waiting", room.getState(), "对局应被终止并回到等待加入状态");
        assertNull(room.getSteps(), "对局数据应被清空，不能继续残局");
        assertFalse(room.isReadyId1(), "对局终止后应回到未准备状态");

        //正在巡查的管理员同步看到等待加入状态，而不是旧的对手信息
        String update = awaitTypeContaining(adminClient, "admin-room-update", "\"playerId2\":\"-1\"", TIMEOUT);
        assertNotNull(update, "管理员巡查视图应同步为等待加入");
    }

    // ==================== 3. 封号房主：剩余房客继承房主 ====================

    @Test
    @Order(3)
    void banOwnerTransfersOwnershipToGuest() throws Exception {
        room2 = createRoom(client3, id3);
        joinRoom(client4, id4, room2);

        adminClient.send("{\"type\":\"admin-enter-room\",\"roomId\":\"" + room2 + "\"}");
        assertNotNull(adminClient.await("admin-room-view", TIMEOUT));

        adminClient.send("{\"type\":\"admin-ban\",\"targetId\":\"" + id3 + "\"}");
        assertNotNull(client3.await("banned", TIMEOUT), "被封号房主应收到通知");

        String becomeOwner = client4.await("become-owner", TIMEOUT);
        assertNotNull(becomeOwner, "剩余房客应收到继承房主的通知");
        JSONObject content = JSONUtil.parseObj(becomeOwner).getJSONObject("content");
        assertEquals(id4, content.getStr("playerId1"), "剩余房客应成为新房主");
        assertEquals("-1", content.getStr("playerId2"), "原房主位置应变为等待加入");

        Room room = SocketServer.roomMap.get(room2);
        assertNotNull(room);
        assertEquals(id4, room.getPlayerId1(), "房间房主应切换为剩余用户");
        assertEquals("-1", room.getPlayerId2());
        assertEquals("waiting", room.getState(), "继承房主后应回到等待加入状态");

        String update = awaitTypeContaining(adminClient, "admin-room-update", id4, TIMEOUT);
        assertNotNull(update, "管理员巡查视图应同步房主变更");
    }

    // ==================== 4. 房间仅剩一人被封号：解散房间并让管理员回到大厅 ====================

    @Test
    @Order(4)
    void banSoleOwnerDissolvesRoomAndClosesAdminView() throws Exception {
        room3 = createRoom(client5, id5);

        adminClient.send("{\"type\":\"admin-enter-room\",\"roomId\":\"" + room3 + "\"}");
        assertNotNull(adminClient.await("admin-room-view", TIMEOUT));

        adminClient.send("{\"type\":\"admin-ban\",\"targetId\":\"" + id5 + "\"}");
        assertNotNull(client5.await("banned", TIMEOUT), "被封号用户应收到通知");

        String closed = adminClient.await("admin-room-closed", TIMEOUT);
        assertNotNull(closed, "房间被解散后正在巡查的管理员应收到回到大厅的通知");
        assertTrue(closed.contains("解散"), "通知应说明房间已解散：" + closed);
        assertNull(SocketServer.roomMap.get(room3), "仅剩一人被封号后房间应被解散");

//清理仍在查看的房间观察者，避免影响后续用例
        adminClient.send("{\"type\":\"admin-leave-room\",\"roomId\":\"" + room1 + "\"}");
        adminClient.send("{\"type\":\"admin-leave-room\",\"roomId\":\"" + room2 + "\"}");
        client4.send("{\"type\":\"quit-room\"}");
        Thread.sleep(200);
    }

    // ==================== 5. 巡查中接受邀请：退出巡查并成为房内玩家 ====================

    @Test
    @Order(5)
    void adminAcceptsInviteLeavesObserverMode() throws Exception {
        //管理员先重新巡查 room1，再由房主邀请管理员加入该房间
        adminClient.send("{\"type\":\"admin-enter-room\",\"roomId\":\"" + room1 + "\"}");
        assertNotNull(adminClient.await("admin-room-view", TIMEOUT), "管理员应重新进入巡查");

        int inviteBefore = adminClient.count("invite-received");
        client1.send("{\"type\":\"invite\",\"targetId\":\"" + adminId + "\"}");
        assertTrue(adminClient.awaitCount("invite-received", inviteBefore + 1, TIMEOUT),
                "巡查中的管理员应收到邀请");

        int joinBefore = adminClient.count("join-room-success");
        adminClient.send("{\"type\":\"invite-accept\",\"roomId\":\"" + room1 + "\",\"fromId\":\"" + id1 + "\"}");
        assertTrue(adminClient.awaitCount("join-room-success", joinBefore + 1, TIMEOUT),
                "接受邀请后管理员应进入房间");
        assertEquals(room1, SocketServer.playerRoomMap.get(adminId),
                "接受邀请后管理员应成为该房间玩家");
        assertFalse(SocketServer.isAdminObserver(room1, adminId),
                "接受邀请进房后不应继续保留该房间的巡查观察者身份");

        adminClient.send("{\"type\":\"quit-room\"}");
        client1.send("{\"type\":\"quit-room\"}");
        Thread.sleep(200);
    }

    // ==================== 清理 ====================

    @AfterAll
    void cleanUp() {
        for (TestWsClient client : new TestWsClient[]{adminClient, client1, client2, client3, client4, client5}) {
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
                //清理阶段忽略单条失败，避免影响其它用例
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

    private String userJson(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + nameById.get(id) + "\"}";
    }

    private String createRoom(TestWsClient client, String id) throws Exception {
        int before = client.count("create-room-success");
        client.send("{\"type\":\"create-room\",\"user\":" + userJson(id) + "}");
        assertTrue(client.awaitCount("create-room-success", before + 1, TIMEOUT), "创建房间应成功");
        return JSONUtil.parseObj(lastOfType(client, "create-room-success"))
                .getJSONObject("content").getStr("id");
    }

    private void joinRoom(TestWsClient client, String id, String roomId) throws Exception {
        int before = client.count("join-room-success");
        client.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":" + userJson(id) + "}");
        assertTrue(client.awaitCount("join-room-success", before + 1, TIMEOUT), "加入房间应成功");
    }

    private String lastOfType(TestWsClient client, String type) {
        List<String> messages = client.ofType(type);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /** 等待一条包含指定内容且类型为 chat-message 的消息（房内聊天可能夹杂其它系统消息） */
    private String awaitChatContaining(TestWsClient client, String keyword, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (String message : client.messages()) {
                if (message.contains("\"type\":\"chat-message\"") && message.contains(keyword)) {
                    return message;
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    /** 等待某类型消息中出现指定片段（用于管理员巡查的增量更新） */
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