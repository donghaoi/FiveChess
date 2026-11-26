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
 * 「对局结束后双方准备即自动开下一局」这条链路的专项用例。
 *
 * 覆盖点：
 * 1. 连续多局：每局只下发一次开局、局序号逐局递增、棋盘每局重新清空；
 * 2. 对局进行中重复上报准备不会重复开局、也不会清空当前棋盘；
 * 3. 对手未准备时可以取消准备，取消后不会开局；
 * 4. 上一局延迟到达的认输不会落到新局上（局序号校验）；
 * 5. 不带局序号的老客户端仍可正常认输（兼容）；
 * 6. 和棋自动开新局后立刻认输不会被上一条结算记录误挡（去重按局隔离）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoundFlowTests {

    private static final long TIMEOUT = 4000L;
    private static final Random RANDOM = new Random();
    private static final String PASSWORD = "rf123456";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private static final List<String> logins = new ArrayList<>();
    private static final Map<String, String> nameById = new HashMap<>();
    private static final Map<String, String> tokenById = new HashMap<>();

    private static TestWsClient playerA;
    private static TestWsClient playerB;
    private static String idA;
    private static String idB;
    private static String nickA;
    private static String nickB;

    @BeforeAll
    void setUp() throws Exception {
        idA = registerAndLogin("rda");
        idB = registerAndLogin("rdb");
        playerA = TestWsClient.connectAuthed(port, idA, tokenById.get(idA));
        playerB = TestWsClient.connectAuthed(port, idB, tokenById.get(idB));
        assertNotNull(playerA.await("auth-success", TIMEOUT), "A 应用令牌鉴权成功");
        assertNotNull(playerB.await("auth-success", TIMEOUT), "B 应用令牌鉴权成功");
        String suffix = String.format("%04d", RANDOM.nextInt(10000));
        nickA = "轮甲" + suffix;
        nickB = "轮乙" + suffix;
        playerA.send("{\"type\":\"report-nick\",\"userId\":\"" + idA + "\",\"nickName\":\"" + nickA + "\"}");
        playerB.send("{\"type\":\"report-nick\",\"userId\":\"" + idB + "\",\"nickName\":\"" + nickB + "\"}");
        Thread.sleep(200);
    }

    /**
     * 连续三局都靠「双方准备」开局：每局只下发一次开局，局序号逐局递增，棋盘每局重新清空。
     */
    @Test
    @Order(1)
    void threeRoundsStartByReady() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        Room room = SocketServer.roomMap.get(roomId);
        long lastVersion = -1L;
        for (int round = 1; round <= 3; round++) {
            int startA = playerA.count("start-game");
            int startB = playerB.count("start-game");
            playerA.send(readyJson(idA, idB, true));
            playerB.send(readyJson(idB, idA, true));
            assertTrue(playerA.awaitCount("start-game", startA + 1, TIMEOUT), "第 " + round + " 局：A 应自动开局");
            assertTrue(playerB.awaitCount("start-game", startB + 1, TIMEOUT), "第 " + round + " 局：B 应自动开局");
            assertEquals("playing", room.getState(), "第 " + round + " 局：状态应为游戏中");
            assertNotNull(room.getSteps(), "第 " + round + " 局：应初始化棋局");
            assertEquals(0, room.getSteps().size(), "第 " + round + " 局：棋盘应为空");
            assertTrue(room.isReadyId1() && room.isReadyId2(), "第 " + round + " 局：开局后双方应仍为已准备");

            long version = versionOf(playerA);
            assertTrue(version > lastVersion, "第 " + round + " 局的局序号应比上一局大");
            lastVersion = version;
            //重复上报一次准备：对局已开始，不允许再触发一次开局
            int startedA = playerA.count("start-game");
            playerA.send(readyJson(idA, idB, true));
            playerA.send(readyJson(idA, idB, true));
            Thread.sleep(150);
            assertEquals(startedA, playerA.count("start-game"), "第 " + round + " 局：重复准备不应重复开局");
            assertEquals(0, room.getSteps().size(), "第 " + round + " 局：重复准备不应清空当前棋盘");

            //认输结束本局，进入「双方重新准备」的阶段
            int resetA = playerA.count("round-reset");
            int resetB = playerB.count("round-reset");
            playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId
                    + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB
                    + "\",\"boardVersion\":" + version + "}");
            assertTrue(playerA.awaitCount("round-reset", resetA + 1, TIMEOUT), "第 " + round + " 局：认输方应收到回合结束");
            assertTrue(playerB.awaitCount("round-reset", resetB + 1, TIMEOUT), "第 " + round + " 局：对手应收到回合结束");
            assertEquals("room", room.getState(), "第 " + round + " 局结束后应回到房间中");
            assertFalse(room.isReadyId1(), "第 " + round + " 局结束后房主应回到未准备");
            assertFalse(room.isReadyId2(), "第 " + round + " 局结束后房客应回到未准备");
            assertNull(room.getSteps(), "第 " + round + " 局结束后棋局数据应清空");
        }
        leaveAll();
    }

    /**
     * 对局进行中重复上报准备：不清棋盘、不重复开局、局序号不变。
     */
    @Test
    @Order(2)
    void extraReadyWhilePlayingIsIgnored() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        long version = versionOf(playerA);

        String blackId = "black".equals(room.getColor1()) ? idA : idB;
        String whiteId = blackId.equals(idA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        String blackColor = blackId.equals(idA) ? room.getColor1() : room.getColor2();
        black.send(playJson(roomId, 3, 3, blackColor, blackId, whiteId));
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline && room.getSteps().size() < 1) {
            Thread.sleep(20);
        }
        assertEquals(1, room.getSteps().size(), "测试落子应被服务端接受");

        int startA = playerA.count("start-game");
        int startB = playerB.count("start-game");
        playerA.send(readyJson(idA, idB, true));
        playerB.send(readyJson(idB, idA, true));
        Thread.sleep(300);
        assertEquals(startA, playerA.count("start-game"), "对局中不应重复下发开局");
        assertEquals(startB, playerB.count("start-game"), "对局中不应重复下发开局");
        assertEquals(1, room.getSteps().size(), "对局中重复准备不应清空棋盘");
        assertEquals(version, versionOf(playerA), "对局中重复准备不应改变局序号");
        leaveAll();
    }

    /**
     * 对手未准备时可以取消准备；取消后即使对手准备也不开局，自己重新准备才开局。
     */
    @Test
    @Order(3)
    void cancelReadyBeforeOpponent() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        Room room = SocketServer.roomMap.get(roomId);

        int startA = playerA.count("start-game");
        playerA.send(readyJson(idA, idB, true));
        waitUntil(() -> room.isReadyId1(), "A 的准备状态应被记录");
        playerA.send(readyJson(idA, idB, false));
        waitUntil(() -> !room.isReadyId1(), "A 取消准备后状态应被清除");
        assertEquals("room", room.getState(), "单人取消准备后房间应保持「房间中」");

        playerB.send(readyJson(idB, idA, true));
        waitUntil(() -> room.isReadyId2(), "B 的准备状态应被记录");
        Thread.sleep(250);
        assertEquals(startA, playerA.count("start-game"), "一方已取消准备时不应开局");
        assertEquals("room", room.getState(), "只有一方准备时应保持「房间中」");

        playerA.send(readyJson(idA, idB, true));
        assertTrue(playerA.awaitCount("start-game", startA + 1, TIMEOUT), "双方重新准备后应开局");
        assertEquals("playing", room.getState(), "开局后应为游戏中");
        leaveAll();
    }

    /**
     * 上一局发出的认输延迟到达：棋局已重开，必须被丢弃，不能把新局判负。
     */
    @Test
    @Order(4)
    void staleSurrenderFromPreviousRoundRejected() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        long oldVersion = versionOf(playerA);

        //A 请求重开，B 同意 → 服务端直接开新局，局序号自增
        int startB = playerB.count("start-game");
        playerA.send("{\"type\":\"restart\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\"}");
        assertNotNull(playerB.await("restart", TIMEOUT), "B 应收到重开请求");
        playerB.send("{\"type\":\"restart-agree\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idB
                + "\",\"to\":\"" + idA + "\",\"boardVersion\":" + oldVersion + "}");
        assertTrue(playerB.awaitCount("start-game", startB + 1, TIMEOUT),
                "同意重开后应开新局，B 收到的消息：" + playerB.messages() + " 房间状态：" + room.getState());
        long newVersion = versionOf(playerB);
        assertTrue(newVersion > oldVersion, "重开后局序号应自增");

        //上一局遗留的认输（带旧局序号）到达：应被拒绝，且明确告知本局仍在进行
        int invalidBefore = playerA.count("action-invalid");
        int resetBefore = playerA.count("round-reset");
        playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\",\"boardVersion\":" + oldVersion + "}");
        assertTrue(playerA.awaitCount("action-invalid", invalidBefore + 1, TIMEOUT), "过期认输应被拒绝");
        JSONObject invalid = JSONUtil.parseObj(lastOfType(playerA, "action-invalid"));
        assertFalse(invalid.getBool("roundOver"), "过期认输被拒时应告知本局仍在进行");
        assertTrue(invalid.getStr("content").contains("上一局"), "应提示这是上一局的认输");
        assertEquals("playing", room.getState(), "新局应继续，不受过期认输影响");
        assertEquals(0, room.getSteps().size(), "新局棋盘不应被改动");
        assertEquals(resetBefore, playerA.count("round-reset"), "过期认输不应产生回合结束");
        leaveAll();
    }

    /**
     * 老客户端（不带局序号）认输仍应正常生效：兼容性不能被新校验破坏。
     */
    @Test
    @Order(5)
    void surrenderWithoutVersionStillWorks() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);

        int resetA = playerA.count("round-reset");
        int resetB = playerB.count("round-reset");
        playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId
                + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        assertTrue(playerA.awaitCount("round-reset", resetA + 1, TIMEOUT), "不带局序号的认输也应生效");
        assertTrue(playerB.awaitCount("round-reset", resetB + 1, TIMEOUT), "对手应收到回合结束");
        assertEquals("room", room.getState(), "认输后应回到房间中");
        assertFalse(room.isReadyId1() && room.isReadyId2(), "认输后双方应回到未准备");
        leaveAll();
    }

    /**
     * 和棋通过后服务端立刻开新局：新局开头的认输不能被上一条结算记录（去重窗口）误挡。
     */
    @Test
    @Order(6)
    void surrenderRightAfterPeaceRestartWorks() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        long version = versionOf(playerA);

        int peaceRestartB = playerB.count("peace-restart");
        playerA.send("{\"type\":\"peace\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\"}");
        assertNotNull(playerB.await("peace", TIMEOUT), "B 应收到和棋请求");
        playerB.send("{\"type\":\"peace-agree\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idB
                + "\",\"to\":\"" + idA + "\",\"boardVersion\":" + version + "}");
        assertTrue(playerB.awaitCount("peace-restart", peaceRestartB + 1, TIMEOUT),
                "和棋通过后应自动开新局，B 收到的消息：" + playerB.messages() + " 房间状态：" + room.getState());
        long newVersion = versionOf(playerB);
        assertTrue(newVersion > version, "和棋重开后局序号应自增");

        //紧接着（结算去重窗口内）在新局认输：属于新一局，必须生效
        int resetA = playerA.count("round-reset");
        playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\",\"boardVersion\":" + newVersion + "}");
        assertTrue(playerA.awaitCount("round-reset", resetA + 1, TIMEOUT),
                "和棋重开后的新局认输应立即生效，不应被上一局的结算去重挡住");
        assertEquals("room", room.getState(), "认输后房间应回到房间中");
        leaveAll();
    }

    /**
     * 双方高频重复上报准备（模拟连点 / 网络重发）：只允许开一局，不能反复清盘或重复下发开局。
     */
    @Test
    @Order(7)
    void readyStormStartsSingleGame() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        int startA = playerA.count("start-game");
        int startB = playerB.count("start-game");
        for (int i = 0; i < 10; i++) {
            playerA.send(readyJson(idA, idB, true));
            playerB.send(readyJson(idB, idA, true));
        }
        Thread.sleep(1200);
        assertEquals(startA + 1, playerA.count("start-game"), "连点准备只能开一局（A）");
        assertEquals(startB + 1, playerB.count("start-game"), "连点准备只能开一局（B）");
        Room room = SocketServer.roomMap.get(roomId);
        assertEquals("playing", room.getState(), "连点准备后应处于对局中");
        assertNotNull(room.getSteps(), "连点准备后应有棋局数据");
        assertEquals(0, room.getSteps().size(), "连点准备不应在棋盘上留下任何落子");
        leaveAll();
    }

    /**
     * 实时比分：五连获胜方 +1、认输时胜方 +1，房间视图（round-reset）同步带上比分；
     * 有人离开、只剩一人时比分清零，不会带到下一对搭档身上。
     */
    @Test
    @Order(8)
    void scoreTracksWinsAndResetsWhenPlayerLeaves() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        startByReady(playerA, playerB);
        Room room = SocketServer.roomMap.get(roomId);
        assertEquals(0, room.getScoreId1(), "新房间比分应从 0 开始");
        assertEquals(0, room.getScoreId2(), "新房间比分应从 0 开始");

        //黑棋横向连成五子（(0,0)-(4,0)），白棋在第二行交错落子不构成五连
        int resetBefore = playerA.count("round-reset");
        int[][] moves = {{0, 0}, {0, 1}, {1, 0}, {1, 1}, {2, 0}, {2, 1}, {3, 0}, {3, 1}, {4, 0}};
        for (int[] move : moves) {
            playMove(roomId, room, move[0], move[1]);
        }
        assertTrue(playerA.awaitCount("round-reset", resetBefore + 1, TIMEOUT), "五连后应收到回合结束");
        boolean blackIsHost = "black".equals(room.getColor1());
        assertEquals(blackIsHost ? 1 : 0, room.getScoreId1(), "执黑获胜时房主比分 +1");
        assertEquals(blackIsHost ? 0 : 1, room.getScoreId2(), "执黑获胜时房客比分不变");
        //round-reset 携带的房间视图必须带比分，前端据此刷新上方比分条
        JSONObject resetView = JSONUtil.parseObj(lastOfType(playerA, "round-reset")).getJSONObject("roomView");
        assertTrue(resetView.containsKey("scoreId1") && resetView.containsKey("scoreId2"),
                "回合结束的房间视图应携带比分字段");
        assertEquals(room.getScoreId1(), resetView.getInt("scoreId1"), "视图中的房主比分应与服务端一致");
        assertEquals(room.getScoreId2(), resetView.getInt("scoreId2"), "视图中的房客比分应与服务端一致");

        //第二局：双方各下 3 手（达到计入对局记录的最低步数）后 A 认输，胜利应记到玩家2头上
        startByReady(playerA, playerB);
        long version = versionOf(playerA);
        int[][] sixMoves = {{5, 5}, {6, 5}, {7, 5}, {8, 5}, {9, 5}, {10, 5}};
        for (int[] move : sixMoves) {
            playMove(roomId, room, move[0], move[1]);
        }
        int score1Before = room.getScoreId1();
        int score2Before = room.getScoreId2();
        int resetA = playerA.count("round-reset");
        playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\",\"boardVersion\":" + version + "}");
        assertTrue(playerA.awaitCount("round-reset", resetA + 1, TIMEOUT), "认输后应收到回合结束");
        assertEquals(score1Before, room.getScoreId1(), "认输方不应得分");
        assertEquals(score2Before + 1, room.getScoreId2(), "认输时对手应得分");

        //房主退出、房间只剩一人：比分清零，不会带到下一对搭档
        playerA.send("{\"type\":\"quit-room\"}");
        waitUntil(() -> room.getScoreId1() == 0 && room.getScoreId2() == 0,
                "房主离开后比分应清零，实际：" + room.getScoreId1() + ":" + room.getScoreId2());
        playerB.send("{\"type\":\"quit-room\"}");
        Thread.sleep(200);
    }

    @AfterAll
    void cleanUp() {
        if (playerA != null) playerA.close();
        if (playerB != null) playerB.close();
        for (String id : new String[]{idA, idB}) {
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

    /**
     * 最近一次开局下发的局序号：按消息到达顺序取最后一个带 boardVersion 的开局类消息。
     * 注意不能取「历史最大值」——同一个连接跨用例复用，旧房间的序号会比新房间大。
     */
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

    /**
     * 按当前轮次自动选出该落子的一方，用服务端记录的颜色下发一手，并等待服务端接收。
     * 最后一手若恰好构成五连，服务端会结算并清空棋局（steps 变为 null），因此判定放宽为「已增长或已结算」。
     */
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

    /**
     * 当前该谁落子：开局由执黑一方先手，之后双方交替（与服务端 expectedPlayer 口径一致）
     */
    private String expectedId(Room room) {
        if (room.getSteps() == null || room.getSteps().isEmpty()) {
            return "black".equals(room.getColor1()) ? room.getPlayerId1() : room.getPlayerId2();
        }
        String lastPlayer = String.valueOf(room.getSteps().get(room.getSteps().size() - 1).getPlayer());
        return lastPlayer.equals(room.getPlayerId1()) ? room.getPlayerId2() : room.getPlayerId1();
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
        Thread.sleep(200);
    }

    private String userJson(String id) {
        String login = nameById.get(id);
        return "{\"id\":\"" + id + "\",\"name\":\"" + login + "\"}";
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
        nameById.put(id, login);
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
