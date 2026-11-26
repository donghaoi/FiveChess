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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全边界回归测试：
 * 身份绑定、开局条件、房间切换/观战状态、登录信息泄露、令牌注销等。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityRegressionTests {

    private static final long TIMEOUT = 6000L;
    private static final Random RANDOM = new Random();
    private static final String PASSWORD = "sr123456";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> logins = new ArrayList<>();
    private final Map<String, String> tokenById = new HashMap<>();
    private final Map<String, String> loginById = new HashMap<>();

    private String idA;
    private String idB;
    private String idC;
    private String idD;
    private TestWsClient a;
    private TestWsClient b;
    private TestWsClient c;

    @BeforeAll
    void setUp() throws Exception {
        idA = registerAndLogin("sra");
        idB = registerAndLogin("srb");
        idC = registerAndLogin("src");
        idD = registerAndLogin("srd");
        a = connect(idA);
        b = connect(idB);
        c = connect(idC);
    }

    @AfterAll
    void tearDown() {
        leaveAll();
        for (TestWsClient client : new TestWsClient[]{a, b, c}) {
            if (client != null) {
                client.close();
            }
        }
        for (String id : new String[]{idA, idB, idC, idD}) {
            if (id != null) {
                jdbc.update("delete from record where player1 = ? or player2 = ?", Integer.valueOf(id), Integer.valueOf(id));
                jdbc.update("delete from user where id = ?", Integer.valueOf(id));
            }
        }
    }

    @Test
    @Order(1)
    void loginErrorsDoNotRevealAccountExistence() {
        JSONObject wrongPassword = post("/loginPost",
                "{\"login\":\"" + loginById.get(idA) + "\",\"password\":\"wrong-password\"}");
        JSONObject unknownAccount = post("/loginPost",
                "{\"login\":\"sr_no_such_account\",\"password\":\"wrong-password\"}");

        assertEquals(401, wrongPassword.getInt("code"));
        assertEquals(401, unknownAccount.getInt("code"));
        assertEquals(wrongPassword.getStr("message"), unknownAccount.getStr("message"),
                "账号不存在与密码错误必须返回完全相同的提示，避免账号枚举");
    }

    @Test
    @Order(2)
    void bannedIpDoesNotBlockOtherAccountLogin() {
        // 模拟一个已封号账号：它的注册来源就是当前 IP，因此该 IP 不能注册新账号，
        // 但不应影响同 IP 的其他正常账号登录
        jdbc.update("update user set banned = 1 where id = ?", Integer.valueOf(idB));
        try {
            JSONObject login = post("/loginPost",
                    "{\"login\":\"" + loginById.get(idD) + "\",\"password\":\"" + PASSWORD + "\"}");
            assertEquals(200, login.getInt("code"), "同 IP 上未被封号的账号应能正常登录：" + login);
            assertNotNull(login.getJSONObject("content").getStr("token"));
        } finally {
            jdbc.update("update user set banned = 0, ban_time = null where id = ?", Integer.valueOf(idB));
        }
    }

    @Test
    @Order(3)
    void createRoomBindsToConnectionIdentity() throws Exception {
        a.send("{\"type\":\"create-room\",\"user\":{\"id\":\"" + idB + "\",\"name\":\"伪造昵称\"}}");
        JSONObject created = JSONUtil.parseObj(a.await("create-room-success", TIMEOUT));
        assertNotNull(created, "创建房间应成功");
        String roomId = created.getJSONObject("content").getStr("id");
        assertEquals(idA, created.getJSONObject("content").getStr("playerId1"),
                "房间房主必须是当前连接账号，不能是客户端上报的 user.id");
        assertNull(SocketServer.playerRoomMap.get(idB), "被冒用账号不应被关联到房间");
        leaveAll();
    }

    @Test
    @Order(4)
    void joinRoomBindsToConnectionIdentity() throws Exception {
        String roomId = createRoom(a);
        b.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":{\"id\":\"" + idC + "\",\"name\":\"伪造昵称\"}}");
        JSONObject joined = JSONUtil.parseObj(b.await("join-room-success", TIMEOUT));
        assertNotNull(joined, "加入房间应成功");
        assertEquals(idB, joined.getJSONObject("content").getStr("playerId2"),
                "房间成员必须是当前连接账号，不能是客户端上报的 user.id");
        assertNull(SocketServer.playerRoomMap.get(idC), "被冒用账号不应被关联到房间");
        leaveAll();
    }

    @Test
    @Order(5)
    void switchingRoomsRemovesOldMembership() throws Exception {
        String room1 = createRoom(a);
        joinRoom(b, room1);
        String room2 = createRoom(c);
        int leaveBefore = a.count("client-leave");
        //记录切换前的成功计数：b 可能在此前用例中已经加入过房间，直接等 1 条会误把旧消息当成这次成功
        int joinBefore = b.count("join-room-success");
        b.send("{\"type\":\"join-room\",\"roomId\":\"" + room2 + "\",\"user\":{\"id\":\"" + idB + "\",\"name\":\"\"}}");
        assertTrue(b.awaitCount("join-room-success", joinBefore + 1, TIMEOUT), "切换到第二个房间应成功");
        assertTrue(a.awaitCount("client-leave", leaveBefore + 1, TIMEOUT), "原房主应收到房客离开通知");
        Room oldRoom = SocketServer.roomMap.get(room1);
        assertNotNull(oldRoom, "原房间仍应存在");
        assertEquals("-1", oldRoom.getPlayerId2(), "切换房间后原房间不应残留旧成员");
        assertEquals(room2, SocketServer.playerRoomMap.get(idB), "用户只应关联到新房间");
        leaveAll();
    }

    @Test
    @Order(6)
    void startGameRequiresBothReadyAndIgnoresDuplicateStart() throws Exception {
        String roomId = createRoom(a);
        joinRoom(b, roomId);
        Room room = SocketServer.roomMap.get(roomId);

        // 未准备：单方 start-game 必须被拒绝
        int failBefore = a.count("start-game-fail");
        a.send("{\"type\":\"start-game\",\"roomId\":\"" + roomId + "\"}");
        assertTrue(a.awaitCount("start-game-fail", failBefore + 1, TIMEOUT), "未准备时开局应被拒绝");
        assertNull(room.getColor1(), "未准备时不应分配棋子颜色");

        // 双方准备后由服务端自动开局
        int startBefore = a.count("start-game");
        ready(a, idA, idB);
        ready(b, idB, idA);
        assertTrue(a.awaitCount("start-game", startBefore + 1, TIMEOUT), "双方准备后应能自动开局");
        String color1 = room.getColor1();
        assertNotNull(color1, "开局后应记录颜色");

        // 落一子，再由另一方重复发送 start-game：不能清空棋盘/重新随机颜色
        String blackId = "black".equals(color1) ? idA : idB;
        String whiteId = "black".equals(color1) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? a : b;
        int stepsBefore = room.getSteps().size();
        black.send("{\"type\":\"play\",\"x\":0,\"y\":0,\"roomId\":\"" + roomId
                + "\",\"from\":\"" + blackId + "\",\"to\":\"" + whiteId + "\"}");
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline && room.getSteps().size() == stepsBefore) {
            Thread.sleep(20);
        }
        assertEquals(1, room.getSteps().size(), "第一手应被记录");

        int secondStart = b.count("start-game");
        b.send("{\"type\":\"start-game\",\"roomId\":\"" + roomId + "\"}");
        Thread.sleep(300);
        assertEquals(1, room.getSteps().size(), "重复 start-game 不能清空已开始的棋局");
        assertEquals(color1, room.getColor1(), "重复 start-game 不能重新随机颜色");
        assertEquals(secondStart, b.count("start-game"), "重复 start-game 不应再次广播开局");
        leaveAll();
    }

    @Test
    @Order(7)
    void spectateFinishedRoomIsAllowed() throws Exception {
        String roomId = createRoom(a);
        joinRoom(b, roomId);
        int startBefore = a.count("start-game");
        ready(a, idA, idB);
        ready(b, idB, idA);
        assertTrue(a.awaitCount("start-game", startBefore + 1, TIMEOUT), "应正常开局");
        SocketServer.roomMap.get(roomId).setState("over");

        // 已结束的对局允许进入观战：前端会提示“对局已结束，是否继续观看”
        int successBefore = c.count("spectate-success");
        c.send("{\"type\":\"spectate\",\"targetId\":\"" + idA + "\"}");
        assertTrue(c.awaitCount("spectate-success", successBefore + 1, TIMEOUT), "已结束的对局应允许观战");
        JSONObject view = JSONUtil.parseObj(lastOfType(c, "spectate-success")).getJSONObject("roomView");
        assertEquals("over", view.getStr("state"), "观战视图应标记对局已结束");
        assertEquals(roomId, SocketServer.playerRoomMap.get(idC), "观战后应关联到该房间");
        c.send("{\"type\":\"quit-room\"}");
        Thread.sleep(100);
        leaveAll();
    }

    @Test
    @Order(8)
    void spectatorCreateRoomDoesNotDestroyWatchedRoom() throws Exception {
        String roomId = createRoom(a);
        joinRoom(b, roomId);
        int startBefore = a.count("start-game");
        ready(a, idA, idB);
        ready(b, idB, idA);
        assertTrue(a.awaitCount("start-game", startBefore + 1, TIMEOUT), "应正常开局");

        int specBefore = c.count("spectate-success");
        c.send("{\"type\":\"spectate\",\"targetId\":\"" + idA + "\"}");
        assertTrue(c.awaitCount("spectate-success", specBefore + 1, TIMEOUT),
                "观战应成功；失败原因：" + lastOfType(c, "spectate-fail")
                        + "；房间状态：" + SocketServer.roomMap.get(roomId).getState()
                        + "；步骤：" + SocketServer.roomMap.get(roomId).getSteps()
                        + "；观战者映射：" + SocketServer.playerRoomMap.get(idC));

        // 观战者直接新建房间：只退出观战，被观战的房间与两名玩家必须保持原状
        int createBefore = c.count("create-room-success");
        c.send("{\"type\":\"create-room\"}");
        assertTrue(c.awaitCount("create-room-success", createBefore + 1, TIMEOUT), "观战者新建房间应成功");
        JSONObject created = JSONUtil.parseObj(lastOfType(c, "create-room-success"));
        assertNotNull(created);
        String newRoomId = created.getJSONObject("content").getStr("id");
        assertNotEquals(roomId, newRoomId);
        assertNotNull(SocketServer.roomMap.get(roomId), "被观战的房间不应被解散");
        assertEquals(roomId, SocketServer.playerRoomMap.get(idA), "原房主仍应留在原房间");
        assertEquals(roomId, SocketServer.playerRoomMap.get(idB), "原房客仍应留在原房间");
        assertEquals(newRoomId, SocketServer.playerRoomMap.get(idC), "观战者应进入自己的新房间");
        leaveAll();
    }

    @Test
    @Order(9)
    void logoutRevokesToken() {
        JSONObject login = post("/loginPost",
                "{\"login\":\"" + loginById.get(idD) + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, login.getInt("code"));
        String token = login.getJSONObject("content").getStr("token");
        assertNotNull(token);

        JSONObject pageBefore = post("/getPage",
                "{\"id\":\"" + idD + "\",\"pageNum\":\"1\",\"token\":\"" + token + "\"}");
        assertEquals(200, pageBefore.getInt("code"), "注销前令牌应有效");

        JSONObject logout = post("/logoutPost", "{\"token\":\"" + token + "\"}");
        assertEquals(200, logout.getInt("code"));

        JSONObject pageAfter = post("/getPage",
                "{\"id\":\"" + idD + "\",\"pageNum\":\"1\",\"token\":\"" + token + "\"}");
        assertEquals(401, pageAfter.getInt("code"), "注销后旧令牌必须立即失效");
    }

    @Test
    @Order(10)
    void reportNickCannotImpersonateRegisteredNickname() throws Exception {
        String registeredNameOfB = loginById.get(idB);
        a.send("{\"type\":\"report-nick\",\"userId\":\"" + idA + "\",\"nickName\":\"" + registeredNameOfB + "\"}");
        // 鉴权成功时服务端已按数据库昵称把本人写入在线名单，本次上报被纠正回同一昵称，
        // 属于「昵称未变化」——服务端不会为此再全服广播（否则高频上报会放大广播）。
        // 因此这里不强制要求新的 online-user-list，只校验纠正后的最终状态。
        Thread.sleep(500);
        JSONObject infoA = findOnlineUser(a, idA);
        assertNotNull(infoA, "在线列表应包含当前用户");
        assertNotEquals(registeredNameOfB, infoA.getStr("nick"),
                "不能把自己显示成其他已注册账号的昵称");
        assertEquals(loginById.get(idA), infoA.getStr("nick"),
                "冒用昵称时应回退为当前账号的真实昵称");
    }

    @Test
    @Order(11)
    void reservedAdminLoginCannotBeRegistered() {
        // 测试配置中管理员账号名为 modadm（见 pom.xml 的 surefire 配置）
        JSONObject resp = post("/registerPost",
                "{\"id\":\"modadm\",\"name\":\"reserved-admin-probe\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(403, resp.getInt("code"), "管理员账号名必须保留，不能通过注册接口抢占");
        assertEquals("该账号为管理员账号，禁止使用！", resp.getStr("message"));
    }

    @Test
    @Order(11)
    void reservedAdminWordsCannotBeUsedAsLoginOrNickname() {
        // 账号：管理员 / admin（不区分大小写）都不允许注册
        JSONObject cnLogin = post("/registerPost",
                "{\"id\":\"管理员\",\"name\":\"reserved-cn-login\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(403, cnLogin.getInt("code"), "账号「管理员」应被拒绝");
        assertEquals("该账号为管理员账号，禁止使用！", cnLogin.getStr("message"));

        JSONObject enLogin = post("/registerPost",
                "{\"id\":\"ADMIN\",\"name\":\"reserved-en-login\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(403, enLogin.getInt("code"), "账号「ADMIN」应被拒绝（不区分大小写）");
        assertEquals("该账号为管理员账号，禁止使用！", enLogin.getStr("message"));

        // 昵称：管理员 / admin（不区分大小写）都不允许注册
        // 用随机后缀而不是 System.nanoTime()：每次 mvn test 都是新 JVM，nanoTime 起点相近，
        // 取模后很容易和上一次运行留下的测试账号撞车，导致「普通账号应能注册」误报 401
        String probeSuffix = String.format("%06d", Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 1000000);
        String probeLogin = "rk" + probeSuffix;
        JSONObject cnName = post("/registerPost",
                "{\"id\":\"" + probeLogin + "\",\"name\":\"管理员\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(403, cnName.getInt("code"), "昵称「管理员」应被拒绝");
        assertEquals("该昵称为管理员昵称，禁止使用！", cnName.getStr("message"));

        JSONObject enName = post("/registerPost",
                "{\"id\":\"" + probeLogin + "b\",\"name\":\"Admin\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(403, enName.getInt("code"), "昵称「Admin」应被拒绝（不区分大小写）");
        assertEquals("该昵称为管理员昵称，禁止使用！", enName.getStr("message"));

        // 普通账号与昵称不受影响
        JSONObject ok = post("/registerPost",
                "{\"id\":\"" + probeLogin + "c\",\"name\":\"普通玩家" + probeSuffix + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, ok.getInt("code"), "普通账号与昵称应能正常注册");
        //临时账号立即清理，避免残留数据让下次运行撞名
        jdbc.update("delete from user where login like ?", probeLogin + "%");
    }

    @Test
    @Order(12)
    void excessivePageNumberIsRejected() {
        JSONObject page = post("/getPage",
                "{\"id\":\"" + idA + "\",\"pageNum\":\"200000\",\"token\":\"" + tokenById.get(idA) + "\"}");
        assertEquals(400, page.getInt("code"));
        assertTrue(page.getStr("message").contains("页码过大"));
    }

    // ==================== helpers ====================

    private TestWsClient connect(String id) throws Exception {
        TestWsClient client = TestWsClient.connectAuthed(port, id, tokenById.get(id));
        assertNotNull(client.await("auth-success", TIMEOUT), "鉴权应成功");
        return client;
    }

    private String registerAndLogin(String prefix) {
        String login = prefix + String.format("%06d", RANDOM.nextInt(1000000));
        logins.add(login);
        JSONObject register = post("/registerPost",
                "{\"id\":\"" + login + "\",\"name\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, register.getInt("code"), "注册失败：" + register);
        JSONObject loginResp = post("/loginPost",
                "{\"login\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, loginResp.getInt("code"), "登录失败：" + loginResp);
        JSONObject content = loginResp.getJSONObject("content");
        String id = String.valueOf(content.getInt("id"));
        loginById.put(id, login);
        tokenById.put(id, content.getStr("token"));
        return id;
    }

    private JSONObject post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertNotNull(response.getBody(), "响应体不应为空：" + path);
        return JSONUtil.parseObj(response.getBody());
    }

    private String createRoom(TestWsClient client) throws Exception {
        int before = client.count("create-room-success");
        client.send("{\"type\":\"create-room\"}");
        assertTrue(client.awaitCount("create-room-success", before + 1, TIMEOUT), "创建房间应成功");
        return JSONUtil.parseObj(lastOfType(client, "create-room-success"))
                .getJSONObject("content").getStr("id");
    }

    private void joinRoom(TestWsClient client, String roomId) throws Exception {
        int before = client.count("join-room-success");
        client.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\"}");
        assertTrue(client.awaitCount("join-room-success", before + 1, TIMEOUT), "加入房间应成功");
    }

    private void ready(TestWsClient client, String from, String to) throws Exception {
        client.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + from + "\",\"to\":\"" + to + "\"}");
    }

    private void leaveAll() {
        for (TestWsClient client : new TestWsClient[]{a, b, c}) {
            if (client != null) {
                try {
                    client.send("{\"type\":\"quit-room\"}");
                } catch (Exception ignored) {
                }
            }
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
    }

    private String lastOfType(TestWsClient client, String type) {
        List<String> messages = client.ofType(type);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    private JSONObject findOnlineUser(TestWsClient client, String userId) {
        List<String> lists = client.ofType("online-user-list");
        for (int i = lists.size() - 1; i >= 0; i--) {
            JSONObject message = JSONUtil.parseObj(lists.get(i));
            JSONObject content = message.getJSONObject("content");
            if (content == null) {
                continue;
            }
            JSONArray infos = content.getJSONArray("userInfos");
            if (infos == null) {
                continue;
            }
            for (int j = 0; j < infos.size(); j++) {
                JSONObject info = infos.getJSONObject(j);
                if (userId.equals(info.getStr("id"))) {
                    return info;
                }
            }
        }
        return null;
    }
}