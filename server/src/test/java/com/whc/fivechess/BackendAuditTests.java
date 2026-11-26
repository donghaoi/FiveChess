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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上线前的后端专项审计用例：只覆盖此前回归没有触及的边界与对抗场景。
 *
 * 1. 换人 / 踢人 / 无效短局后，房间不能继续下发上一局的终局快照（重连会看到“幽灵棋盘”）；
 * 2. 观战者、房客的越权操作一律不能改动对局状态；
 * 3. 认输洪泛、非法落子洪泛后服务端状态仍然自洽且可继续对局；
 * 4. 对局中断线重连：房间、棋盘、局序号完整恢复且能继续落子；
 * 5. 对局结束后双方并发准备：只开一局，局序号只自增一次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendAuditTests {

    private static final long TIMEOUT = 5000L;
    private static final Random RANDOM = new Random();
    private static final String PASSWORD = "au123456";
    /** 黑棋横向连成五子的落子顺序（黑先手）：(0,0)~(4,0)，白棋在 y=1 交错落子 */
    private static final int[][] FIVE_IN_ROW = {
            {0, 0}, {0, 1}, {1, 0}, {1, 1}, {2, 0}, {2, 1}, {3, 0}, {3, 1}, {4, 0}
    };

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private static final List<String> logins = new ArrayList<>();
    private static final Map<String, String> tokenById = new HashMap<>();

    private TestWsClient playerA;
    private TestWsClient playerB;
    private TestWsClient playerC;
    private String idA;
    private String idB;
    private String idC;

    @BeforeAll
    void setUp() throws Exception {
        idA = registerAndLogin("aua");
        idB = registerAndLogin("aub");
        idC = registerAndLogin("auc");
        playerA = connect(idA);
        playerB = connect(idB);
        playerC = connect(idC);
        String suffix = String.format("%04d", RANDOM.nextInt(10000));
        reportNick(playerA, idA, "审计甲" + suffix);
        reportNick(playerB, idB, "审计乙" + suffix);
        reportNick(playerC, idC, "审计丙" + suffix);
        Thread.sleep(200);
    }

    /**
     * 一局打完（产生终局快照）后房客离开：房间回到「等待加入」，终局快照必须一并清掉，
     * 新房客入座时拿到的房间视图必须是「未开局」，否则前端会把上一局的棋盘当成当前局面。
     */
    @Test
    @Order(1)
    void partnerLeaveClearsFinalBoard() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        playFiveInRow(roomId, room);
        assertNotNull(room.getFinalSteps(), "一局结束后应保留终局快照，供结束后继续观战");

        playerB.send("{\"type\":\"quit-room\"}");
        waitUntil(() -> room.getFinalSteps() == null,
                "房客离开后终局快照应清空，实际仍保留 " +
                        (room.getFinalSteps() == null ? 0 : room.getFinalSteps().size()) + " 步");
        assertEquals("waiting", room.getState(), "房客离开后房间应回到等待加入");
        assertNull(room.getSteps(), "房客离开后不应还留有实时棋局");

        //新房客入座：视图必须是「未开局」，不能把上一位对手的终局当成当前局面
        int before = playerC.count("join-room-success");
        playerC.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":" + userJson(idC) + "}");
        assertTrue(playerC.awaitCount("join-room-success", before + 1, TIMEOUT), "新房客应能加入");
        JSONObject view = JSONUtil.parseObj(lastOfType(playerC, "join-room-success"))
                .getJSONObject("content");
        assertFalse(view.getBool("started"), "新房客看到的房间不应处于「已开局」状态");
        assertNull(room.getFinalSteps(), "新房客入座后终局快照仍应为空");

        playerC.send("{\"type\":\"quit-room\"}");
        playerA.send("{\"type\":\"quit-room\"}");
        Thread.sleep(200);
    }

    /**
     * 不足 3 回合的认输不计入记录：这一局留下的临时局面和上一局的终局快照都必须清掉，
     * 否则重连时会把「上一局完整棋盘」当成正在进行的对局。
     */
    @Test
    @Order(2)
    void shortSurrenderClearsFinalBoard() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        //第一局打满五连，制造一份终局快照
        playFiveInRow(roomId, room);
        assertNotNull(room.getFinalSteps(), "第一局结束后应存在终局快照");

        //第二局只下两手就认输：不足最低记录步数，不写记录
        startByReady(playerA, playerB);
        long version = versionOf(playerA);
        playMove(roomId, room, 6, 6);
        playMove(roomId, room, 6, 7);
        int resetA = playerA.count("round-reset");
        playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\",\"boardVersion\":" + version + "}");
        assertTrue(playerA.awaitCount("round-reset", resetA + 1, TIMEOUT), "认输后应收到回合结束");

        assertEquals("room", room.getState(), "短局认输后房间应回到房间中");
        assertNull(room.getSteps(), "短局认输后实时棋局应清空");
        assertNull(room.getFinalSteps(), "短局认输后不应保留上一局的终局快照");
        leaveAll();
    }

    /**
     * 观战者不是对局成员：落子、准备、认输、踢人、切换房间可见性全部不得生效。
     */
    @Test
    @Order(3)
    void spectatorCannotControlGame() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);

        int before = playerC.count("spectate-success");
        playerC.send("{\"type\":\"spectate\",\"targetId\":\"" + idA + "\"}");
        assertTrue(playerC.awaitCount("spectate-success", before + 1, TIMEOUT), "观战应成功");

        int resetA = playerA.count("round-reset");
        int resetB = playerB.count("round-reset");
        int kickBefore = playerC.count("kick-fail");
        //观战者用伪造的身份发起对局操作
        playerC.send(playJson(roomId, 3, 3, "black", idC, idA));
        playerC.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idC + "\",\"to\":\"" + idA + "\"}");
        playerC.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idC
                + "\",\"to\":\"" + idA + "\"}");
        playerC.send("{\"type\":\"regret-agree\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idC
                + "\",\"to\":\"" + idA + "\",\"backNum\":\"2\"}");
        playerC.send("{\"type\":\"kick-player\",\"targetId\":\"" + idA + "\"}");
        playerC.send("{\"type\":\"toggle-room-open\",\"open\":false,\"password\":\"1234\"}");
        Thread.sleep(600);

        assertEquals(0, room.getSteps().size(), "观战者的落子不能进入棋盘");
        assertEquals("playing", room.getState(), "观战者的认输不能让对局结束");
        assertEquals(0, room.getScoreId1() + room.getScoreId2(), "不应产生任何比分");
        assertEquals(resetA, playerA.count("round-reset"), "房主不应收到回合结束");
        assertEquals(resetB, playerB.count("round-reset"), "房客不应收到回合结束");
        assertTrue(playerC.count("kick-fail") > kickBefore, "观战者踢人应被拒绝");
        assertTrue(room.isOpen(), "观战者不能把房间改成私人房间");
        assertNotNull(SocketServer.roomMap.get(roomId), "房间不应被解散");

        playerC.send("{\"type\":\"quit-room\"}");
        leaveAll();
    }

    /**
     * 房客既不是房主也不是管理员：踢人、切换可见性、解散房间都必须被服务端拒绝。
     */
    @Test
    @Order(4)
    void guestCannotKickOrAdministerRoom() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        Room room = SocketServer.roomMap.get(roomId);

        int kickBefore = playerB.count("kick-fail");
        playerB.send("{\"type\":\"kick-player\",\"targetId\":\"" + idA + "\"}");
        assertTrue(playerB.awaitCount("kick-fail", kickBefore + 1, TIMEOUT), "房客踢房主应被拒绝");
        assertEquals(idA, room.getPlayerId1(), "房主不能被房客踢走");

        int openBefore = playerB.count("room-open-fail");
        playerB.send("{\"type\":\"toggle-room-open\",\"open\":false,\"password\":\"1234\"}");
        assertTrue(playerB.awaitCount("room-open-fail", openBefore + 1, TIMEOUT), "房客改可见性应被拒绝");
        assertTrue(room.isOpen(), "房间可见性不应被房客修改");

        playerB.send("{\"type\":\"admin-dismiss-room\",\"roomId\":\"" + roomId + "\"}");
        Thread.sleep(300);
        assertNotNull(SocketServer.roomMap.get(roomId), "非管理员不能解散房间");
        assertEquals(idA, SocketServer.roomMap.get(roomId).getPlayerId1(), "房间成员不应被清空");

        leaveAll();
    }

    /**
     * 认输洪泛：同一局只结算一次，比分只加一分，后续请求全部按「本局已结束」拒绝。
     */
    @Test
    @Order(5)
    void surrenderFloodSettlesOnce() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        //先下 6 手，保证超过「不足 3 回合」的门槛，能真正写入结算
        for (int[] move : new int[][]{{5, 5}, {5, 6}, {6, 5}, {6, 6}, {7, 5}, {7, 6}}) {
            playMove(roomId, room, move[0], move[1]);
        }
        long version = versionOf(playerA);
        int resetA = playerA.count("round-reset");
        int resetB = playerB.count("round-reset");
        int score1 = room.getScoreId1();
        int score2 = room.getScoreId2();

        StringBuilder flood = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            flood.append("{\"type\":\"surrender\",\"roomId\":\"").append(roomId)
                    .append("\",\"from\":\"").append(idA).append("\",\"to\":\"").append(idB)
                    .append("\",\"boardVersion\":").append(version).append("}");
            playerA.send(flood.toString());
            flood.setLength(0);
        }
        assertTrue(playerA.awaitCount("round-reset", resetA + 1, TIMEOUT), "认输应结算一次");
        Thread.sleep(600);

        assertEquals(resetA + 1, playerA.count("round-reset"), "认输洪泛只能结算一次（A）");
        assertEquals(resetB + 1, playerB.count("round-reset"), "对手只应收到一次回合结束");
        assertEquals("room", room.getState(), "结算后房间应回到房间中");
        assertEquals(score1 + 0, room.getScoreId1(), "认输方不应得分");
        assertEquals(score2 + 1, room.getScoreId2(), "对手只应得一分");
        leaveAll();
    }

    /**
     * 非法落子洪泛：越界、非数字、重复位置、非本回合落子都不能污染棋盘，
     * 且服务端在处理完之后仍能正常接受合法落子。
     */
    @Test
    @Order(6)
    void illegalPlayFloodKeepsGamePlayable() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        String blackId = expectedId(room);
        String whiteId = blackId.equals(idA) ? idB : idA;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        //非本回合落子：应被拒绝且不进棋盘
        int rejectBefore = white.count("play-reject");
        white.send(playJson(roomId, 1, 1, "white", whiteId, blackId));
        assertTrue(white.awaitCount("play-reject", rejectBefore + 1, TIMEOUT), "非本回合落子应被拒绝");
        assertEquals(0, room.getSteps().size(), "被拒绝的落子不能进入棋盘");

        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        for (int i = 0; i < 40; i++) {
            //越界
            black.send("{\"type\":\"play\",\"x\":-1,\"y\":0,\"roomId\":\"" + roomId
                    + "\",\"from\":\"" + blackId + "\",\"to\":\"" + whiteId + "\"}");
            black.send("{\"type\":\"play\",\"x\":99,\"y\":99,\"roomId\":\"" + roomId
                    + "\",\"from\":\"" + blackId + "\",\"to\":\"" + whiteId + "\"}");
            //非数字坐标
            black.send("{\"type\":\"play\",\"x\":\"abc\",\"y\":0,\"roomId\":\"" + roomId
                    + "\",\"from\":\"" + blackId + "\",\"to\":\"" + whiteId + "\"}");
            //伪造 to（不在本房间）
            black.send("{\"type\":\"play\",\"x\":3,\"y\":3,\"roomId\":\"" + roomId
                    + "\",\"from\":\"" + blackId + "\",\"to\":\"" + idC + "\"}");
            //重复落子
            black.send("{\"type\":\"play\",\"x\":3,\"y\":3,\"roomId\":\"" + roomId
                    + "\",\"from\":\"" + blackId + "\",\"to\":\"" + whiteId + "\"}");
            black.send("{\"type\":\"play\",\"x\":3,\"y\":3,\"roomId\":\"" + roomId
                    + "\",\"from\":\"" + blackId + "\",\"to\":\"" + whiteId + "\"}");
        }
        Thread.sleep(500);
        assertEquals(1, room.getSteps().size(), "洪泛结束后棋盘上只应有那一手合法落子");
        assertEquals("playing", room.getState(), "洪泛不应改变对局状态");

        //对局仍可继续：轮到白棋落子
        String actor = expectedId(room);
        TestWsClient client = actor.equals(idA) ? playerA : playerB;
        String other = actor.equals(idA) ? idB : idA;
        String color = actor.equals(room.getPlayerId1()) ? room.getColor1() : room.getColor2();
        int sizeBefore = room.getSteps().size();
        client.send(playJson(roomId, 8, 8, color, actor, other));
        waitUntil(() -> room.getSteps() == null || room.getSteps().size() > sizeBefore,
                "洪泛后合法落子仍应被接受");
        leaveAll();
    }

    /**
     * 对局中断线重连：房间、棋盘、局序号完整恢复，重连后还能继续落子。
     */
    @Test
    @Order(7)
    void reconnectRestoresBoardAndCanContinue() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        playMove(roomId, room, 2, 2);
        playMove(roomId, room, 2, 3);
        long version = versionOf(playerA);
        assertEquals(2, room.getSteps().size(), "重连前应有 2 手棋");

        //模拟刷新页面：关闭当前连接后立刻用同一账号重新鉴权
        playerA.close();
        TestWsClient reconnected = connect(idA);
        String restore = reconnected.await("room-restore", TIMEOUT);
        assertNotNull(restore, "重连后应收到房间恢复消息");
        JSONObject restoreJson = JSONUtil.parseObj(restore);
        assertEquals("player", restoreJson.getStr("role"), "重连后身份应仍为房内玩家");
        assertEquals(version, restoreJson.getJSONObject("roomView").getLong("boardVersion"),
                "恢复消息里的局序号应与服务端当前局一致");
        JSONArray steps = restoreJson.getJSONArray("steps");
        assertNotNull(steps, "恢复消息应带上当前棋盘");
        assertEquals(2, steps.size(), "恢复的棋盘步数应与服务端一致");
        playerA = reconnected;

        //重连后继续对局：轮到谁就由谁落子
        String actor = expectedId(room);
        TestWsClient client = actor.equals(idA) ? playerA : playerB;
        String other = actor.equals(idA) ? idB : idA;
        String color = actor.equals(room.getPlayerId1()) ? room.getColor1() : room.getColor2();
        client.send(playJson(roomId, 9, 9, color, actor, other));
        waitUntil(() -> room.getSteps() == null || room.getSteps().size() == 3,
                "重连后落子应被服务端接受");
        leaveAll();
    }

    /**
     * 对局结束后双方「几乎同时」准备：只开一局、局序号只自增一次。
     */
    @Test
    @Order(8)
    void concurrentReadyAfterRoundOpensSingleGame() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        long oldVersion = versionOf(playerA);

        //先结束本局
        int resetA = playerA.count("round-reset");
        playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\",\"boardVersion\":" + oldVersion + "}");
        assertTrue(playerA.awaitCount("round-reset", resetA + 1, TIMEOUT), "认输后应收到回合结束");
        waitUntil(() -> !room.isReadyId1() && !room.isReadyId2(), "结算后双方应回到未准备");

        int startA = playerA.count("start-game");
        int startB = playerB.count("start-game");
        CountDownLatch go = new CountDownLatch(1);
        Thread t1 = new Thread(() -> {
            try {
                go.await();
                playerA.send(readyJson(idA, idB, true));
            } catch (Exception ignored) {
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                go.await();
                playerB.send(readyJson(idB, idA, true));
            } catch (Exception ignored) {
            }
        });
        t1.start();
        t2.start();
        go.countDown();
        t1.join(TimeUnit.SECONDS.toMillis(5));
        t2.join(TimeUnit.SECONDS.toMillis(5));

        assertTrue(playerA.awaitCount("start-game", startA + 1, TIMEOUT), "并发准备后应开局");
        Thread.sleep(500);
        assertEquals(startA + 1, playerA.count("start-game"), "并发准备只能开一局（A）");
        assertEquals(startB + 1, playerB.count("start-game"), "并发准备只能开一局（B）");
        assertEquals("playing", room.getState(), "并发准备后应处于对局中");
        assertEquals(0, room.getSteps().size(), "新局棋盘应为空");
        assertEquals(oldVersion + 1, versionOf(playerA), "局序号只应自增一次");
        leaveAll();
    }

    /**
     * 重复上报同一个准备状态属于空操作：不能因此刷新全服在线列表，
     * 否则连点（或恶意高频发送）一条无副作用的指令就会被放大成对所有连接的广播。
     */
    @Test
    @Order(9)
    void repeatedReadyDoesNotAmplifyBroadcast() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        //对局中双方准备状态已经是 true，此时再上报 true 不改变任何状态
        Thread.sleep(200);
        int listBefore = playerC.count("online-user-list");
        for (int i = 0; i < 100; i++) {
            playerA.send(readyJson(idA, idB, true));
        }
        Thread.sleep(500);
        int amplified = playerC.count("online-user-list") - listBefore;
        assertTrue(amplified <= 2,
                "重复准备不应放大成全服在线列表广播，实际增加 " + amplified + " 次");
        assertEquals("playing", room.getState(), "重复准备不能改变对局状态");
        assertNotNull(room.getSteps(), "重复准备不能清空棋局");
        assertEquals(0, room.getSteps().size(), "重复准备不能清空棋盘");
        leaveAll();
    }

    @AfterAll
    void cleanUp() {        closeQuietly(playerA);
        closeQuietly(playerB);
        closeQuietly(playerC);
        for (String id : new String[]{idA, idB, idC}) {
            if (id == null) {
                continue;
            }
            Integer uid = Integer.valueOf(id);
            for (String dataId : jdbc.queryForList(
                    "select data from record where player1 = ? or player2 = ?", String.class, uid, uid)) {
                jdbc.update("delete from game where id = ?", dataId);
            }
            jdbc.update("delete from record where player1 = ? or player2 = ?", uid, uid);
        }
        for (String login : logins) {
            jdbc.update("delete from user where login = ?", login);
        }
    }

    // ==================== 辅助方法 ====================

    private interface Condition {
        boolean ok();
    }

    private void waitUntil(Condition condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            if (condition.ok()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(condition.ok(), message);
    }

    private void closeQuietly(TestWsClient client) {
        if (client != null) {
            client.close();
        }
    }

    private long versionOf(TestWsClient client) {
        long version = -1L;
        for (String message : client.messages()) {
            JSONObject json = JSONUtil.parseObj(message);
            String type = json.getStr("type");
            if (!"start-game".equals(type) && !"restart-agree".equals(type) && !"peace-restart".equals(type)) {
                continue;
            }
            if (json.containsKey("boardVersion")) {
                version = json.getLong("boardVersion");
            }
        }
        return version;
    }

    private String readyJson(String from, String to, boolean ready) {
        return "{\"type\":\"info-ready\",\"content\":" + ready + ",\"from\":\"" + from
                + "\",\"to\":\"" + to + "\"}";
    }

    private void startByReady(TestWsClient a, TestWsClient b) throws Exception {
        int startA = a.count("start-game");
        a.send(readyJson(idA, idB, true));
        b.send(readyJson(idB, idA, true));
        assertTrue(a.awaitCount("start-game", startA + 1, TIMEOUT), "双方准备后应自动开局");
    }

    private String playJson(String roomId, int x, int y, String color, String from, String to) {
        return "{\"type\":\"play\",\"x\":" + x + ",\"y\":" + y + ",\"color\":\"" + color + "\","
                + "\"roomId\":\"" + roomId + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    private void playMove(String roomId, Room room, int x, int y) throws Exception {
        String actor = expectedId(room);
        String color = actor.equals(room.getPlayerId1()) ? room.getColor1() : room.getColor2();
        String other = actor.equals(idA) ? idB : idA;
        TestWsClient client = actor.equals(idA) ? playerA : playerB;
        final int expectedSize = (room.getSteps() == null ? 0 : room.getSteps().size()) + 1;
        client.send(playJson(roomId, x, y, color, actor, other));
        waitUntil(() -> room.getSteps() == null || room.getSteps().size() >= expectedSize,
                "落子 (" + x + "," + y + ") 应被服务端接受");
    }

    /** 打一局五连（黑棋横向连五），用于制造「终局快照」这种状态 */
    private void playFiveInRow(String roomId, Room room) throws Exception {
        int resetBefore = playerA.count("round-reset");
        for (int[] move : FIVE_IN_ROW) {
            playMove(roomId, room, move[0], move[1]);
        }
        assertTrue(playerA.awaitCount("round-reset", resetBefore + 1, TIMEOUT), "五连后应收到回合结束");
    }

    private String expectedId(Room room) {
        if (room.getSteps() == null || room.getSteps().isEmpty()) {
            return "black".equals(room.getColor1()) ? room.getPlayerId1() : room.getPlayerId2();
        }
        String lastPlayer = String.valueOf(room.getSteps().get(room.getSteps().size() - 1).getPlayer());
        return lastPlayer.equals(room.getPlayerId1()) ? room.getPlayerId2() : room.getPlayerId1();
    }

    private TestWsClient connect(String id) throws Exception {
        TestWsClient client = TestWsClient.connectAuthed(port, id, tokenById.get(id));
        assertNotNull(client.await("auth-success", TIMEOUT), "连接应通过令牌鉴权");
        return client;
    }

    private void reportNick(TestWsClient client, String id, String nick) throws Exception {
        client.send("{\"type\":\"report-nick\",\"userId\":\"" + id + "\",\"nickName\":\"" + nick + "\"}");
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

    private void leaveAll() throws Exception {
        playerA.send("{\"type\":\"quit-room\"}");
        playerB.send("{\"type\":\"quit-room\"}");
        Thread.sleep(250);
    }

    private String userJson(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"u" + id + "\"}";
    }

    private String lastOfType(TestWsClient client, String type) {
        List<String> messages = client.ofType(type);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    private String registerAndLogin(String prefix) {
        String login = prefix + String.format("%06d", RANDOM.nextInt(1000000));
        logins.add(login);
        post("/registerPost", "{\"id\":\"" + login + "\",\"name\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        JSONObject response = post("/loginPost", "{\"login\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        JSONObject content = response.getJSONObject("content");
        String id = String.valueOf(content.getInt("id"));
        String token = content.getStr("token");
        assertNotNull(token, "登录应下发访问令牌");
        tokenById.put(id, token);
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
}
