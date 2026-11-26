package com.whc.fivechess;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.domain.Steps;
import com.whc.fivechess.support.TestWsClient;
import com.whc.fivechess.websocket.component.SocketServer;
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

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 全流程功能测试：大厅、创建/加入房间、准备开局、落子、胜负确认保存、
 * 聊天与敏感词、在线列表、异地登录踢下线，以及身份越权的防护校验。
 * 使用真实数据库，测试账号统一使用 sf 前缀，测试结束后清理测试数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SocketFlowTests {

    private static final long TIMEOUT = 4000L;

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Random RANDOM = new Random();
    private static final List<String> logins = new ArrayList<>();
    private static final List<String> gameIds = new ArrayList<>();
private static final Map<String, String> nameById = new HashMap<>();
    private static final Map<String, String> tokenById = new HashMap<>();

    private static TestWsClient playerA;
    private static TestWsClient playerB;
    private static TestWsClient outsider;
    private static String idA;
    private static String idB;
    private static String idC;
    private static String roomId;

    @BeforeAll
    void setUp() throws Exception {
        idA = registerAndLogin("sfa");
        idB = registerAndLogin("sfb");
        idC = registerAndLogin("sfc");
        //连接后必须先完成令牌鉴权，服务端才会处理业务指令
        playerA = TestWsClient.connectAuthed(port, idA, tokenById.get(idA));
        playerB = TestWsClient.connectAuthed(port, idB, tokenById.get(idB));
        outsider = TestWsClient.connectAuthed(port, idC, tokenById.get(idC));
        assertNotNull(playerA.await("auth-success", TIMEOUT), "有效令牌应鉴权成功");
        reportNick(playerA, idA, "棋手A");
        reportNick(playerB, idB, "棋手B");
        reportNick(outsider, idC, "棋手C");
    }

    @Test
    @Order(1)
    void onlineUserListBroadcast() throws Exception {
        assertNotNull(playerA.await("online-user-list", TIMEOUT), "连接后应收到在线用户列表");
        //昵称上报会触发全服刷新，消息异步到达，等待包含全部在线用户的列表
        String listForA = null;
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline && listForA == null) {
            for (String message : playerA.ofType("online-user-list")) {
                if (message.contains("棋手A") && message.contains("棋手B") && message.contains("棋手C")) {
                    listForA = message;
                    break;
                }
            }
            if (listForA == null) {
                Thread.sleep(50);
            }
        }
        assertNotNull(listForA, "在线列表应包含全部在线用户");
        JSONObject content = JSONUtil.parseObj(listForA).getJSONObject("content");
        assertTrue(content.getJSONArray("userList").contains("棋手B"));
        assertTrue(content.getInt("onlineCount") >= 3);
        //userInfos 携带 id/昵称/状态/房主标记，既有 userList/onlineCount 保持不变
        cn.hutool.json.JSONArray infos = content.getJSONArray("userInfos");
        assertNotNull(infos, "在线列表应补充 userInfos 明细");
        assertEquals(content.getJSONArray("userList").size(), infos.size(), "userInfos 与 userList 条数应一致");
        JSONObject infoA = findUserInfo(infos, "棋手A");
        assertNotNull(infoA, "userInfos 应包含棋手A");
        assertEquals(idA, infoA.getStr("id"));
        assertEquals("idle", infoA.getStr("status"), "尚未进入房间应为空闲");
        assertFalse(infoA.getBool("owner"), "尚未创建房间不应标记为房主");
        //服务端在线昵称表应记录三个连接
        assertEquals("棋手A", SocketServer.onlineUserNickMap.get(idA));
        assertEquals("棋手C", SocketServer.onlineUserNickMap.get(idC));
    }

    /**
     * 从在线列表明细中取出指定昵称的条目
     */
    private JSONObject findUserInfo(cn.hutool.json.JSONArray infos, String nick) {
        for (int i = 0; i < infos.size(); i++) {
            JSONObject item = infos.getJSONObject(i);
            if (nick.equals(item.getStr("nick"))) {
                return item;
            }
        }
        return null;
    }

    /**
     * 从最新的在线列表中读取指定昵称的游戏状态（最长等待 TIMEOUT）
     */
    private String onlineStatusOf(TestWsClient client, String nick) throws Exception {
        JSONObject item = awaitUserInfo(client, nick);
        assertNotNull(item, "在线列表应包含 " + nick);
        return item.getStr("status");
    }

    /**
     * 从最新的在线列表中读取指定昵称的房主标记（最长等待 TIMEOUT）
     */
    private Boolean onlineOwnerOf(TestWsClient client, String nick) throws Exception {
        JSONObject item = awaitUserInfo(client, nick);
        assertNotNull(item, "在线列表应包含 " + nick);
        return item.getBool("owner");
    }

    /**
     * 轮询在线列表消息，直到出现包含指定昵称且带 userInfos 的列表
     */
    private JSONObject awaitUserInfo(TestWsClient client, String nick) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            List<String> lists = client.ofType("online-user-list");
            //只取最新一条，避免命中更早的历史列表
            for (int i = lists.size() - 1; i >= 0; i--) {
                String message = lists.get(i);
                if (!message.contains(nick)) {
                    continue;
                }
                cn.hutool.json.JSONArray infos =
                        JSONUtil.parseObj(message).getJSONObject("content").getJSONArray("userInfos");
                if (infos == null) {
                    continue;
                }
                JSONObject item = findUserInfo(infos, nick);
                if (item != null) {
                    return item;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    @Test
    @Order(2)
    void createJoinAndSelfJoinGuard() throws Exception {
        playerA.send("{\"type\":\"create-room\",\"user\":" + userJson(idA) + "}");
        JSONObject created = JSONUtil.parseObj(playerA.await("create-room-success", TIMEOUT));
        assertNotNull(created, "创建房间应返回成功消息");
        roomId = created.getJSONObject("content").getStr("id");
        assertEquals(idA, created.getJSONObject("content").getStr("playerId1"));
        assertEquals("-1", created.getJSONObject("content").getStr("playerId2"));
        //房间视图包含公开标记与观战人数
        assertEquals(true, created.getJSONObject("content").getBool("open"), "新建房间默认公开");
        assertEquals(0, created.getJSONObject("content").getInt("spectatorCount"), "新建房间无观战者");
        assertEquals(roomId, SocketServer.playerRoomMap.get(idA));

        //不能加入自己创建的房间
        playerA.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":" + userJson(idA) + "}");
        JSONObject selfJoin = JSONUtil.parseObj(playerA.await("join-room-fail", TIMEOUT));
        assertNotNull(selfJoin, "加入自己的房间应收到失败提示");
        assertTrue(selfJoin.getStr("content").contains("不能加入自己创建的房间"));
        assertEquals("-1", roomOf(roomId).getPlayerId2());

        //第二个玩家正常加入
        playerB.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":" + userJson(idB) + "}");
String ownerNoticeRaw = playerA.await("someone-join-room", TIMEOUT);
        assertNotNull(ownerNoticeRaw, "房主应收到有人加入房间的通知");
        JSONObject ownerNotice = JSONUtil.parseObj(ownerNoticeRaw);
        assertEquals(idB, ownerNotice.getJSONObject("content").getStr("playerId2"));
        String joinSuccessRaw = playerB.await("join-room-success", TIMEOUT);
        assertNotNull(joinSuccessRaw, "加入者应收到加入成功消息");
        JSONObject joinSuccess = JSONUtil.parseObj(joinSuccessRaw);
        assertEquals("room", joinSuccess.getJSONObject("content").getStr("state"),
                "两人刚坐下但都未准备，房间状态应为「房间中」");
        assertEquals(roomId, SocketServer.playerRoomMap.get(idB));
//状态联动：双方已坐下但都未准备，应记为“房间中”；未进房者为“空闲”
        assertEquals("inRoom", onlineStatusOf(playerA, "棋手A"));
        assertEquals("inRoom", onlineStatusOf(playerA, "棋手B"));
        assertEquals("idle", onlineStatusOf(playerA, "棋手C"));
        //双方都准备后，状态才变为“游戏中”
        int listBefore = playerA.count("online-user-list");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        //先等服务端记录准备状态，再等两份准备各自触发的在线列表刷新都已送达
        long readyDeadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < readyDeadline
                && !(SocketServer.roomMap.get(roomId).isReadyId1() && SocketServer.roomMap.get(roomId).isReadyId2())) {
            Thread.sleep(50);
        }
        assertTrue(SocketServer.roomMap.get(roomId).isReadyId1()
                && SocketServer.roomMap.get(roomId).isReadyId2(), "双方准备状态应被服务端记录");
        assertTrue(playerA.awaitCount("online-user-list", listBefore + 2, TIMEOUT), "准备状态变化应刷新在线列表");
        assertEquals("inGame", onlineStatusOf(playerA, "棋手A"), "双方准备后状态应为游戏中");
        assertEquals("inGame", onlineStatusOf(playerA, "棋手B"), "双方准备后状态应为游戏中");
        //房主标记：房主账号被标记，房客不标记
        assertEquals(true, onlineOwnerOf(playerA, "棋手A"));
        assertEquals(false, onlineOwnerOf(playerA, "棋手B"));

        //房间相关广播只下发视图字段，不再携带房间内的完整步数
        assertFalse(ownerNoticeRaw.contains("\"steps\""), "房间广播不应携带完整步数");
        assertFalse(joinSuccessRaw.contains("\"steps\""), "加入房间的消息不应携带完整步数");
        for (String roomList : playerA.ofType("online-rooms")) {
            assertFalse(roomList.contains("\"steps\""), "在线房间列表不应携带房间步数");
        }

        //房间已满，第三个人加入失败
        outsider.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":" + userJson(idC) + "}");
        JSONObject full = JSONUtil.parseObj(outsider.await("join-room-fail", TIMEOUT));
        assertNotNull(full, "房间已满应收到失败提示");
        assertTrue(full.getStr("content").contains("满了"));
        assertNull(SocketServer.playerRoomMap.get(idC));

        //不存在的房间
        outsider.send("{\"type\":\"join-room\",\"roomId\":\"no-such-room\",\"user\":" + userJson(idC) + "}");
        assertNotNull(outsider.await("join-room-fail", TIMEOUT));
    }

    @Test
    @Order(3)
    void readyAndStartGame() throws Exception {
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        JSONObject readyForA = JSONUtil.parseObj(playerA.await("info-ready", TIMEOUT));
        assertNotNull(readyForA, "对手准备消息应转发给房主");
        assertTrue(readyForA.getBool("content"));

        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        assertNotNull(playerB.await("info-ready", TIMEOUT), "准备消息应转发给房客");

        //双方都准备后服务端直接开局，无需客户端再发 start-game
        JSONObject startA = JSONUtil.parseObj(playerA.await("start-game", TIMEOUT));
        JSONObject startB = JSONUtil.parseObj(playerB.await("start-game", TIMEOUT));
        assertNotNull(startA, "开局应通知房主");
        assertNotNull(startB, "开局应通知房客");
        //双方棋子颜色互补
        assertNotEquals(startA.getStr("content"), startB.getStr("content"), "双方棋子颜色不应相同");
        assertNotNull(roomOf(roomId).getSteps(), "开局后房间应初始化步数列表");
        assertEquals(0, roomOf(roomId).getSteps().size());
        assertNotNull(roomOf(roomId).getStart(), "开局后应记录开局时间");
    }

    @Test
    @Order(4)
void playTurnCheckAndWinByServer() throws Exception {
        //从上一步 start-game 消息中取回双方颜色
        String colorOfA = JSONUtil.parseObj(lastOfType(playerA, "start-game")).getStr("content");
        String colorOfB = JSONUtil.parseObj(lastOfType(playerB, "start-game")).getStr("content");
        assertNotEquals(colorOfA, colorOfB, "双方棋子颜色不应相同");

        boolean aIsBlack = "black".equals(colorOfA);
        TestWsClient black = aIsBlack ? playerA : playerB;
        TestWsClient white = aIsBlack ? playerB : playerA;
        String blackId = aIsBlack ? idA : idB;
        String whiteId = aIsBlack ? idB : idA;

        //不是该自己落子时，服务端拒绝并回 play-reject，且不记录步数
        int beforeReject = white.count("play-reject");
        white.send(playJson(5, 5, "white", whiteId, blackId));
        assertTrue(white.awaitCount("play-reject", beforeReject + 1, TIMEOUT), "先后手由服务端判定，非本方回合应被拒绝");
        assertEquals(0, roomOf(roomId).getSteps().size(), "被拒绝的落子不应写入步数");

        //黑棋连成五子：胜负由服务端判定，服务端直接落库
        int[][] blackPoints = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {4, 0}};
        int[][] whitePoints = {{0, 1}, {1, 1}, {2, 1}, {3, 1}};
        int expected = 0;
        for (int i = 0; i < blackPoints.length; i++) {
            sendMove(black, roomId, blackPoints[i][0], blackPoints[i][1], "black", blackId, whiteId, ++expected);
            if (i < whitePoints.length) {
                sendMove(white, roomId, whitePoints[i][0], whitePoints[i][1], "white", whiteId, blackId, ++expected);
            }
        }
        JSONObject overForBlack = JSONUtil.parseObj(black.await("game-over", TIMEOUT));
        JSONObject overForWhite = JSONUtil.parseObj(white.await("game-over", TIMEOUT));
        assertNotNull(overForBlack, "判定胜负后双方都应收到对局结果");
        assertNotNull(overForWhite, "判定胜负后双方都应收到对局结果");
assertEquals(Integer.valueOf(blackId), overForBlack.getInt("winId"));
        assertEquals(Integer.valueOf(blackId), overForWhite.getInt("winId"));
        //连五坐标随终局下发，胜负双方拿到同一份数据，端上棋盘显示效果据此保持一致
        JSONArray lineForBlack = overForBlack.getJSONArray("line");
        JSONArray lineForWhite = overForWhite.getJSONArray("line");
        assertNotNull(lineForBlack, "终局消息应包含连五坐标");
        assertNotNull(lineForWhite, "终局消息应包含连五坐标");
        assertEquals(5, lineForBlack.size(), "连五坐标应包含 5 颗棋子");
        assertEquals(lineForBlack.toString(), lineForWhite.toString(), "胜负双方收到的连五坐标应完全一致");
        assertEquals(0, lineForBlack.getJSONObject(0).getInt("x"), "连五起点横坐标应与落子一致");
        assertEquals(4, lineForBlack.getJSONObject(4).getInt("x"), "连五终点横坐标应与落子一致");

        Map<String, Object> record = awaitRecord();
        assertNotNull(record, "服务端判定胜负后应写入对局记录");
        assertEquals(Integer.valueOf(idA), record.get("player1"));
        assertEquals(Integer.valueOf(idB), record.get("player2"));
        assertEquals(nameById.get(idA), record.get("name1"));
        //数据库 record.win 为 varchar 列，JDBC 原始值为字符串
        assertEquals(blackId, String.valueOf(record.get("win")));
        assertEquals("normal", record.get("type"));
        assertEquals(9, ((Number) record.get("steps")).intValue());
        assertNotNull(record.get("start"), "对局记录应包含开局时间");
        assertNotNull(record.get("end"), "对局记录应包含结束时间");

        //对局数据可反序列化，步数、落子方与棋子颜色均由服务端记录
        String gameId = String.valueOf(record.get("data"));
        gameIds.add(gameId);
        byte[] blob = jdbc.queryForObject("select data from game where id = ?", byte[].class, gameId);
        assertNotNull(blob);
        Steps steps;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(blob))) {
            steps = (Steps) in.readObject();
        }
        assertEquals(9, steps.getSteps().size());
        assertEquals(Integer.valueOf(blackId), steps.getSteps().get(0).getPlayer());
        assertEquals("black", steps.getSteps().get(0).getColor());
        assertEquals(0, steps.getSteps().get(0).getX());

        //保存后房间对局数据被清空，房间状态标记为已结束
        assertNull(roomOf(roomId).getSteps(), "保存后应清空房间步数");
        assertEquals("over", roomOf(roomId).getState());
    }

    @Test
    @Order(5)
    void forgedQuitRoomRejected() throws Exception {
        //第三方用别人的 userId 退出房间，不影响真实玩家的房间归属
        outsider.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        Thread.sleep(300);
        assertEquals(roomId, SocketServer.playerRoomMap.get(idA), "伪造的退出请求不应移除他人房间");
        assertEquals(roomId, SocketServer.playerRoomMap.get(idB));
        assertNotNull(SocketServer.roomMap.get(roomId));
    }

    @Test
    @Order(6)
    void forgedRelayRejected() throws Exception {
        int before = outsider.count("peace");
        //发送者只能把消息转发给自己的对手，指向无关用户的消息必须被丢弃
        playerA.send("{\"type\":\"peace\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idC + "\"}");
        Thread.sleep(500);
        assertEquals(before, outsider.count("peace"), "指向无关用户的消息不应被转发");

        //转发给真实对手正常
        playerA.send("{\"type\":\"peace\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        JSONObject peaceForB = JSONUtil.parseObj(playerB.await("peace", TIMEOUT));
        assertNotNull(peaceForB, "和棋请求应转发给对手");
        assertEquals(idA, peaceForB.getStr("from"));
    }

    @Test
    @Order(7)
    void roomChatAndSensitiveWords() throws Exception {
        int beforeA = playerA.count("chat-message");
        playerA.send("{\"type\":\"chat-message\",\"roomId\":\"" + roomId + "\",\"content\":\"这里有海洛因和冰毒\","
                + "\"fromId\":\"" + idB + "\",\"fromName\":\"伪造的昵称\",\"time\":\"2026-01-01 00:00:00\"}");
        assertTrue(playerB.awaitCount("chat-message", 1, TIMEOUT), "对手应收到房间聊天消息");
        String received = lastOfType(playerB, "chat-message");
        JSONObject chat = JSONUtil.parseObj(received);
        //敏感词等长掩码替换
        assertEquals("这里有***和**", chat.getStr("content"));
        //身份与昵称以服务端记录为准，伪造的 fromId/fromName 不生效
        assertEquals(Integer.valueOf(idA), chat.getInt("fromId"));
        assertEquals(nameById.get(idA), chat.getStr("fromName"));
        assertTrue(playerA.count("chat-message") > beforeA, "自己也应收到房间内消息");

        //超过长度上限的消息被丢弃
        int beforeOver = playerB.count("chat-message");
        StringBuilder over = new StringBuilder();
        for (int i = 0; i < 2001; i++) {
            over.append("x");
        }
        playerA.send("{\"type\":\"chat-message\",\"roomId\":\"" + roomId + "\",\"content\":\"" + over + "\"}");
        Thread.sleep(500);
        assertEquals(beforeOver, playerB.count("chat-message"), "超长聊天消息不应下发");
    }

    @Test
    @Order(8)
    void publicChatBroadcast() throws Exception {
        int beforeC = outsider.count("public-chat-message");
        playerA.send("{\"type\":\"public-chat-message\",\"content\":\"大家好 大麻\"}");
        assertTrue(outsider.awaitCount("public-chat-message", beforeC + 1, TIMEOUT), "公共聊天应全服广播");
        JSONObject publicChat = JSONUtil.parseObj(lastOfType(outsider, "public-chat-message"));
        assertEquals(idA, publicChat.getStr("senderId"));
        assertEquals("棋手A", publicChat.getStr("senderNick"));
        assertEquals("大家好 **", publicChat.getStr("content"));
        assertNotNull(publicChat.getStr("time"));
    }

    @Test
    @Order(9)
void duplicateLoginKicked() throws Exception {
        String idD = registerAndLogin("sfd");
        String tokenD = tokenById.get(idD);
        TestWsClient first = TestWsClient.connectAuthed(port, idD, tokenD);
        assertNotNull(first.await("auth-success", TIMEOUT), "首个连接应鉴权成功");
        TestWsClient second = TestWsClient.connectAuthed(port, idD, tokenD);
        assertNotNull(first.await("kicked", TIMEOUT), "同一账号再次登录应收到被踢下线通知");
        assertTrue(first.awaitClosed(TIMEOUT), "被踢的旧连接应被关闭");
        assertNotNull(second.await("auth-success", TIMEOUT), "新连接应鉴权成功");
        assertNotNull(second.await("online-rooms", TIMEOUT), "新连接应正常使用");
        // 顶号只关闭旧连接，账号本身仍在线（新连接已接管），因此在线昵称必须保留。
        // 鉴权通过后服务端已按数据库昵称写入名单，不依赖客户端再发 report-nick。
        assertEquals(nameById.get(idD), SocketServer.onlineUserNickMap.get(idD),
                "顶号后账号仍在线，昵称应保留为账号真实昵称");
        first.close();
        second.close();
    }

    @Test
    @Order(16)
    void offlineGraceKeepsUserOnlineForQuickReconnect() throws Exception {
        //连接断开后有短暂宽限期：刷新页面这类“立刻重连”不会让其他用户的在线列表出现掉线又上线的闪烁
        SocketServer.setOfflineGraceMs(1500);
        try{
            String idG = registerAndLogin("sfg");
            String tokenG = tokenById.get(idG);
            TestWsClient first = TestWsClient.connectAuthed(port, idG, tokenG);
            assertNotNull(first.await("auth-success", TIMEOUT), "首个连接应鉴权成功");
            first.send("{\"type\":\"report-nick\",\"userId\":\"" + idG + "\",\"nickName\":\"宽限用户\"}");
            Thread.sleep(300);
            assertEquals("宽限用户", SocketServer.onlineUserNickMap.get(idG));

            //断开后仍处于宽限期：在线状态应保留，其他用户不会看到该用户离线
            first.close();
            Thread.sleep(400);
            assertEquals("宽限用户", SocketServer.onlineUserNickMap.get(idG), "宽限期内应保留在线状态");

            //宽限期内重连：应取消延迟下线
            TestWsClient again = TestWsClient.connectAuthed(port, idG, tokenG);
            assertNotNull(again.await("auth-success", TIMEOUT), "重连应鉴权成功");
            again.send("{\"type\":\"report-nick\",\"userId\":\"" + idG + "\",\"nickName\":\"宽限用户\"}");
            Thread.sleep(2000);
            assertEquals("宽限用户", SocketServer.onlineUserNickMap.get(idG), "重连后不应被延迟下线");
            again.close();

            //宽限期内未再重连：宽限期结束后应移出在线列表
            Thread.sleep(2000);
            assertNull(SocketServer.onlineUserNickMap.get(idG), "宽限期结束后仍未重连应移出在线列表");
        }finally{
            //恢复默认宽限期，避免影响其它用例
            SocketServer.setOfflineGraceMs(1500);
        }
    }

    @Test
    @Order(11)
    void websocketRequiresTokenAuth() throws Exception {
        String idE = registerAndLogin("sfe");
        String validToken = tokenById.get(idE);

        //令牌错误：应收到鉴权失败并被断开
        TestWsClient wrong = TestWsClient.connectAuthed(port, idE, "not-a-valid-token");
        assertNotNull(wrong.await("auth-fail", TIMEOUT), "令牌错误应收到鉴权失败");
        assertTrue(wrong.awaitClosed(TIMEOUT), "鉴权失败后连接应被断开");
        wrong.close();

        //完全不鉴权：业务指令不生效，且连接会被服务端清理
        TestWsClient anonymous = TestWsClient.connect(port, "900000001");
        anonymous.send("{\"type\":\"report-nick\",\"userId\":\"900000001\",\"nickName\":\"未鉴权用户\"}");
        anonymous.send("{\"type\":\"create-room\",\"user\":{\"id\":\"900000001\",\"name\":\"未鉴权用户\"}}");
        Thread.sleep(800);
        assertNull(SocketServer.onlineUserNickMap.get("900000001"), "未鉴权连接不应写入在线昵称");
        assertNull(SocketServer.playerRoomMap.get("900000001"), "未鉴权连接不应创建房间");
        assertEquals(0, anonymous.count("create-room-success"), "未鉴权连接不应收到业务响应");
        assertTrue(anonymous.awaitClosed(20000), "未鉴权连接应在超时后被服务端断开");
        anonymous.close();

        //有效令牌可以正常鉴权
        TestWsClient legal = TestWsClient.connectAuthed(port, idE, validToken);
        assertNotNull(legal.await("auth-success", TIMEOUT), "有效令牌应鉴权成功");
        legal.close();
    }

    @Test
    @Order(10)
    void quitRoomRecyclesRoom() throws Exception {
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        JSONObject leaveForA = JSONUtil.parseObj(playerA.await("client-leave", TIMEOUT));
        assertNotNull(leaveForA, "房主应收到房客离开通知");
        assertNull(SocketServer.playerRoomMap.get(idB), "离开后应解除房间关联");
        assertEquals("-1", roomOf(roomId).getPlayerId2());
        assertEquals("waiting", roomOf(roomId).getState());

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        Thread.sleep(500);
        assertNull(SocketServer.roomMap.get(roomId), "最后一人离开后房间应被回收");
        assertNull(SocketServer.playerRoomMap.get(idA));
    }

@Test
    @Order(12)
    void stepLimitEndsWithDraw() throws Exception {
        String drawRoomId = createStartedRoom();
        String colorOfA = SocketServer.roomMap.get(drawRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;
        int beforeOver = playerA.count("game-over");

        //按 ((x + 2y) % 4 < 2) 着色，任意方向都不会出现连续 5 子，构造满盘和棋
        List<int[]> blackCells = new ArrayList<>();
        List<int[]> whiteCells = new ArrayList<>();
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 18; x++) {
                if (((x + 2 * y) % 4) < 2) {
                    blackCells.add(new int[]{x, y});
                } else {
                    whiteCells.add(new int[]{x, y});
                }
            }
        }
        assertEquals(162, blackCells.size());
        assertEquals(162, whiteCells.size());

        int expected = 0;
        for (int i = 0; i < blackCells.size(); i++) {
            sendMove(black, drawRoomId, blackCells.get(i)[0], blackCells.get(i)[1], "black", blackId, whiteId, ++expected);
            sendMove(white, drawRoomId, whiteCells.get(i)[0], whiteCells.get(i)[1], "white", whiteId, blackId, ++expected);
        }
        //棋盘写满仍未连成五子：服务端判和
        assertTrue(playerA.awaitCount("game-over", beforeOver + 1, TIMEOUT), "满盘后应收到对局结果");
        JSONObject drawResult = JSONUtil.parseObj(lastOfType(playerA, "game-over"));
        assertEquals(0, drawResult.getInt("winId"), "和棋 winId 应为 0");

        Map<String, Object> record = awaitRecord();
        assertNotNull(record, "和棋也应写入对局记录");
        assertEquals("peace", record.get("type"));
        assertEquals("0", String.valueOf(record.get("win")));
        assertEquals(324, ((Number) record.get("steps")).intValue(), "满盘对局应记录 324 步");
        gameIds.add(String.valueOf(record.get("data")));
        assertNull(roomOf(drawRoomId).getSteps(), "保存后应清空房间步数");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    @Test
    @Order(13)
    void regretAndShortGameNotSaved() throws Exception {
        String newRoomId = createStartedRoom();
        String colorOfA = SocketServer.roomMap.get(newRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        //先下 6 步（3 回合）
        int expected = 0;
        for (int i = 0; i < 3; i++) {
            sendMove(black, newRoomId, i, 5, "black", blackId, whiteId, ++expected);
            sendMove(white, newRoomId, i, 6, "white", whiteId, blackId, ++expected);
        }
        assertEquals(6, roomOf(newRoomId).getSteps().size());

        //悔棋：白方请求回退 2 步，黑方同意后服务端回退两步
        int beforeRegret = black.count("regret");
        white.send("{\"type\":\"regret\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + whiteId
                + "\",\"to\":\"" + blackId + "\",\"backNum\":2}");
        assertTrue(black.awaitCount("regret", beforeRegret + 1, TIMEOUT), "悔棋请求应转发给对手");
        int beforeAgree = white.count("regret-agree");
        black.send("{\"type\":\"regret-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + blackId
                + "\",\"to\":\"" + whiteId + "\",\"backNum\":2}");
        assertTrue(white.awaitCount("regret-agree", beforeAgree + 1, TIMEOUT), "同意悔棋应通知发起方");
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline && roomOf(newRoomId).getSteps().size() != 4) {
            Thread.sleep(20);
        }
        assertEquals(4, roomOf(newRoomId).getSteps().size(), "悔棋应回退两步");

        //悔棋后轮到黑方（服务端按剩余步数判定），白方抢先落子应被拒绝
        int beforeReject = white.count("play-reject");
        white.send(playJson(newRoomId, 9, 9, "white", whiteId, blackId));
        assertTrue(white.awaitCount("play-reject", beforeReject + 1, TIMEOUT), "悔棋后仍应校验先后手");
        sendMove(black, newRoomId, 2, 5, "black", blackId, whiteId, 5);

        //不足 3 回合的对局即使双方同意和棋也不落库
        int recordsBefore = jdbc.queryForObject("select count(*) from record where player1 = ? and player2 = ?",
                Integer.class, Integer.valueOf(idA), Integer.valueOf(idB));
        int beforePeace = black.count("peace");
        white.send("{\"type\":\"peace\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + whiteId
                + "\",\"to\":\"" + blackId + "\"}");
        assertTrue(black.awaitCount("peace", beforePeace + 1, TIMEOUT), "求和请求应转发给对手");
        black.send("{\"type\":\"peace-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + blackId
                + "\",\"to\":\"" + whiteId + "\"}");
//和棋通过后应自动重新开局：双方都收到 peace-restart，步数清空并重新分配颜色、状态回到对局中
        assertTrue(black.awaitCount("peace-restart", 1, TIMEOUT), "被请求方应收到和棋自动重开通知");
        assertTrue(white.awaitCount("peace-restart", 1, TIMEOUT), "发起方应收到和棋自动重开通知");
        long peaceDeadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < peaceDeadline
                && (roomOf(newRoomId).getSteps() == null || roomOf(newRoomId).getSteps().size() != 0)) {
            Thread.sleep(20);
        }
        assertNotNull(roomOf(newRoomId).getSteps(), "和棋后应重新开局并清空步数");
        assertEquals(0, roomOf(newRoomId).getSteps().size(), "和棋自动重开后步数应为空");
        assertNotNull(roomOf(newRoomId).getColor1(), "和棋自动重开后应重新分配颜色");
        assertEquals("playing", roomOf(newRoomId).getState(), "和棋自动重开后状态应回到对局中");
        assertEquals(recordsBefore, jdbc.queryForObject(
                "select count(*) from record where player1 = ? and player2 = ?",
                Integer.class, Integer.valueOf(idA), Integer.valueOf(idB)), "不足 3 回合的对局不应落库");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    @Test
    @Order(14)
    void surrenderSavesRecord() throws Exception {
        String newRoomId = createStartedRoom();
        String colorOfA = SocketServer.roomMap.get(newRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        int expected = 0;
        for (int i = 0; i < 3; i++) {
            sendMove(black, newRoomId, i, 9, "black", blackId, whiteId, ++expected);
            sendMove(white, newRoomId, i, 10, "white", whiteId, blackId, ++expected);
        }
        //白方认输：服务端保存对局，获胜方为黑方
        int recordsBefore = jdbc.queryForObject("select count(*) from record where player1 = ? and player2 = ?",
                Integer.class, Integer.valueOf(idA), Integer.valueOf(idB));
        int beforeSurrender = black.count("surrender");
        white.send("{\"type\":\"surrender\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + whiteId
                + "\",\"to\":\"" + blackId + "\"}");
        assertTrue(black.awaitCount("surrender", beforeSurrender + 1, TIMEOUT), "对手应收到认输消息");
        long deadline = System.currentTimeMillis() + TIMEOUT;
        int recordsAfter = recordsBefore;
        while (System.currentTimeMillis() < deadline && recordsAfter == recordsBefore) {
            recordsAfter = jdbc.queryForObject("select count(*) from record where player1 = ? and player2 = ?",
                    Integer.class, Integer.valueOf(idA), Integer.valueOf(idB));
            Thread.sleep(50);
        }
        assertEquals(recordsBefore + 1, recordsAfter, "认输后应写入对局记录");
        Map<String, Object> record = awaitRecord();
        assertNotNull(record);
        assertEquals(blackId, String.valueOf(record.get("win")), "认输时获胜方为对手");
        assertEquals("surrender", record.get("type"));
        assertEquals(6, ((Number) record.get("steps")).intValue());
        gameIds.add(String.valueOf(record.get("data")));

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    @Test
    @Order(15)
    void ownerCreatingNewRoomNotifiesOldGuest() throws Exception {
        String oldRoomId = createStartedRoom();
        int beforeClosed = playerB.count("room-closed");
        int beforeCreate = playerA.count("create-room-success");
        //房主再次创建房间：旧房间被解散，并通知旧房间内的另一位玩家
        playerA.send("{\"type\":\"create-room\",\"user\":" + userJson(idA) + "}");
        assertTrue(playerB.awaitCount("room-closed", beforeClosed + 1, TIMEOUT), "房主另建房间应通知旧房客");
        assertTrue(playerA.awaitCount("create-room-success", beforeCreate + 1, TIMEOUT), "房主应创建成功");
        String newRoomId = JSONUtil.parseObj(lastOfType(playerA, "create-room-success"))
                .getJSONObject("content").getStr("id");
        assertNull(SocketServer.roomMap.get(oldRoomId), "旧房间应被解散");
        assertNull(SocketServer.playerRoomMap.get(idB), "旧房客的房间关联应被解除");
        assertEquals(newRoomId, SocketServer.playerRoomMap.get(idA), "房主应关联到新房间");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        Thread.sleep(300);
        assertNull(SocketServer.roomMap.get(newRoomId), "退出后新房间应被回收");
    }

    @AfterAll
    void cleanUp() {
        if (playerA != null) playerA.close();
        if (playerB != null) playerB.close();
        if (outsider != null) outsider.close();
        for (String gameId : gameIds) {
            jdbc.update("delete from record where data = ?", gameId);
            jdbc.update("delete from game where id = ?", gameId);
        }
        for (String login : logins) {
            jdbc.update("delete from record where name1 = ? or name2 = ?", login, login);
            jdbc.update("delete from user where login = ?", login);
        }
    }

    private String registerAndLogin(String prefix) {
        String login = prefix + String.format("%06d", RANDOM.nextInt(1000000));
        logins.add(login);
        post("/registerPost", "{\"id\":\"" + login + "\",\"name\":\"" + login + "\",\"password\":\"sf123456\"}");
JSONObject loginResponse = post("/loginPost", "{\"login\":\"" + login + "\",\"password\":\"sf123456\"}");
        JSONObject content = loginResponse.getJSONObject("content");
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

    private void reportNick(TestWsClient client, String id, String nick) throws Exception {
        client.send("{\"type\":\"report-nick\",\"userId\":\"" + id + "\",\"nickName\":\"" + nick + "\"}");
    }

    private String userJson(String id) {
        String login = nameById.get(id);
        return "{\"id\":\"" + id + "\",\"name\":\"" + login + "\"}";
    }

    // ==================== 双方同时操作（并发）用例 ====================

    /**
     * 双方几乎同时点「认输」：只能按第一次的结果结算，
     * 否则同一局会写入两条对局记录（战绩翻倍），双方还会各收到一次「对手认输」。
     */
    @Test
    @Order(17)
    void simultaneousSurrenderWritesSingleRecord() throws Exception {
        String newRoomId = createStartedRoom();
        String colorOfA = roomOf(newRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        int expected = 0;
        for (int i = 0; i < 3; i++) {
            sendMove(black, newRoomId, i, 11, "black", blackId, whiteId, ++expected);
            sendMove(white, newRoomId, i, 12, "white", whiteId, blackId, ++expected);
        }

        int recordsBefore = jdbc.queryForObject("select count(*) from record where player1 = ? and player2 = ?",
                Integer.class, Integer.valueOf(idA), Integer.valueOf(idB));
        // 两个连接几乎同时发出认输
        playerA.send(surrenderJson(newRoomId, idA, idB));
        playerB.send(surrenderJson(newRoomId, idB, idA));

        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline && !"room".equals(roomOf(newRoomId).getState())) {
            Thread.sleep(20);
        }
        // 留出足够时间让第二条（应被丢弃的）认输处理完
        Thread.sleep(800);
        int recordsAfter = jdbc.queryForObject("select count(*) from record where player1 = ? and player2 = ?",
                Integer.class, Integer.valueOf(idA), Integer.valueOf(idB));
        assertEquals(recordsBefore + 1, recordsAfter, "双方同时认输只能写入一条对局记录");
        assertEquals("room", roomOf(newRoomId).getState(), "认输后房间应回到未开局状态");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    /**
     * 双方几乎同时点「重开」并互相同意：服务端只重置一次。
     * 重置两次会重新随机两次颜色，两端各自收到的最后一条开局面板颜色可能撞成同色。
     */
    @Test
    @Order(18)
    void simultaneousRestartResetsOnlyOnce() throws Exception {
        String newRoomId = createStartedRoom();
        int agreeA = playerA.count("restart-agree");
        int agreeB = playerB.count("restart-agree");
        int startA = playerA.count("start-game");
        int startB = playerB.count("start-game");

        playerA.send("{\"type\":\"restart\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"restart\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        Thread.sleep(80);
        playerA.send("{\"type\":\"restart-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"restart-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");

        Thread.sleep(900);
        com.whc.fivechess.websocket.domain.Room room = roomOf(newRoomId);
        assertNotNull(room, "房间应仍然存在");
        assertTrue(!room.getColor1().equals(room.getColor2()), "重开后双方颜色必须相反");
        assertEquals("playing", room.getState(), "重开后应处于对局中");
        assertNotNull(room.getSteps(), "重开后步数列表应已重置");
        assertEquals(0, room.getSteps().size(), "重开后步数应为空");

        // 每端只应收到一次开局面板消息（start-game 与 restart-agree 合计 1 条）
        int totalA = (playerA.count("start-game") - startA) + (playerA.count("restart-agree") - agreeA);
        int totalB = (playerB.count("start-game") - startB) + (playerB.count("restart-agree") - agreeB);
        assertEquals(1, totalA, "发起方应只被重置一次");
        assertEquals(1, totalB, "响应方应只被重置一次");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    /**
     * 双方几乎同时点「悔棋」并互相同意：服务端只回退一次，
     * 且每次回退的步数由服务端下发，两端不会一端退两步、另一端退四步。
     */
    @Test
    @Order(19)
    void simultaneousRegretRollsBackOnce() throws Exception {
        String newRoomId = createStartedRoom();
        String colorOfA = roomOf(newRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        int expected = 0;
        for (int i = 0; i < 3; i++) {
            sendMove(black, newRoomId, i, 13, "black", blackId, whiteId, ++expected);
            sendMove(white, newRoomId, i, 14, "white", whiteId, blackId, ++expected);
        }
        assertEquals(6, roomOf(newRoomId).getSteps().size());

        int resultBeforeA = playerA.count("regret-agree") + playerA.count("regret-applied");
        playerA.send("{\"type\":\"regret\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\",\"backNum\":2}");
        playerB.send("{\"type\":\"regret\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idB + "\",\"to\":\"" + idA + "\",\"backNum\":2}");
        Thread.sleep(80);
        playerA.send("{\"type\":\"regret-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\",\"backNum\":2}");
        playerB.send("{\"type\":\"regret-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idB + "\",\"to\":\"" + idA + "\",\"backNum\":2}");

        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline
                && roomOf(newRoomId).getSteps() != null && roomOf(newRoomId).getSteps().size() == 6) {
            Thread.sleep(20);
        }
        Thread.sleep(600);
        assertEquals(4, roomOf(newRoomId).getSteps().size(), "双方同时同意悔棋只能回退一次（2 步）");

        // 两端都要收到服务端下发的权威步数，否则本地棋盘无法与服务端对齐
        int resultAfterA = playerA.count("regret-agree") + playerA.count("regret-applied");
        assertTrue(resultAfterA > resultBeforeA, "发起方应收到悔棋结果");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    /**
     * 交叉组合：A 请求悔棋、B 同时请求和棋，双方都点了「同意」。
     * 和棋会把本局结算并重开（局序号 +1）；之后到达的「同意悔棋」属于上一局，
     * 必须作废，否则会把刚开的新局退掉两步，两端棋盘与服务端全部错位。
     */
    @Test
    @Order(20)
    void crossRegretAndPeaceAgreeDoesNotTouchNewGame() throws Exception {
        String newRoomId = createStartedRoom();
        String colorOfA = roomOf(newRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        int expected = 0;
        for (int i = 0; i < 3; i++) {
            sendMove(black, newRoomId, i, 6, "black", blackId, whiteId, ++expected);
            sendMove(white, newRoomId, i, 7, "white", whiteId, blackId, ++expected);
        }
        assertEquals(6, roomOf(newRoomId).getSteps().size());

        // 双方在同一时间各发一个请求：A 悔棋、B 求和。
        // 收发消息在 TestWsClient 里是累计的，所以用 count 差值确认「本次」确实收到，
        // 否则 await 可能直接命中前面用例留下的同类消息
        int regretBeforeB = playerB.count("regret");
        int peaceBeforeA = playerA.count("peace");
        playerA.send("{\"type\":\"regret\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\",\"backNum\":2}");
        playerB.send("{\"type\":\"peace\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idB
                + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerB.awaitCount("regret", regretBeforeB + 1, TIMEOUT), "B 应收到 A 的悔棋请求");
        assertTrue(playerA.awaitCount("peace", peaceBeforeA + 1, TIMEOUT), "A 应收到 B 的和棋请求");

        // 请求转发时必须带上当前局序号，否则对手无法在「同意」时回传
        String regretToB = lastOfType(playerB, "regret");
        String peaceToA = lastOfType(playerA, "peace");
        long regretVersion = JSONUtil.parseObj(regretToB).getLong("boardVersion");
        long peaceVersion = JSONUtil.parseObj(peaceToA).getLong("boardVersion");
        assertEquals(regretVersion, peaceVersion, "同一局内两条请求的局序号应一致");

        // B 同意和棋：本局结算并自动重开，局序号 +1
        int restartBeforeA = playerA.count("peace-restart");
        //和棋请求是 B 发起的，必须由收到请求的 A 同意；
        //让发起方自己同意等于单方面强制判和，服务端会拒绝
        playerA.send("{\"type\":\"peace-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\",\"boardVersion\":" + peaceVersion + "}");
        assertTrue(playerA.awaitCount("peace-restart", restartBeforeA + 1, TIMEOUT),
                "和棋通过后双方应收到重开消息");
        assertEquals(0, roomOf(newRoomId).getSteps().size());

        // 新局里正常落两子
        String colorOfA2 = roomOf(newRoomId).getColor1();
        String blackId2 = "black".equals(colorOfA2) ? idA : idB;
        String whiteId2 = "black".equals(colorOfA2) ? idB : idA;
        TestWsClient black2 = blackId2.equals(idA) ? playerA : playerB;
        TestWsClient white2 = whiteId2.equals(idA) ? playerA : playerB;
        int expected2 = 0;
        sendMove(black2, newRoomId, 0, 2, "black", blackId2, whiteId2, ++expected2);
        sendMove(white2, newRoomId, 0, 3, "white", whiteId2, blackId2, ++expected2);
        assertEquals(2, roomOf(newRoomId).getSteps().size());

        // A 这时才同意上一局的那条悔棋：必须被判定过期
        int invalidBefore = playerA.count("action-invalid");
        playerA.send("{\"type\":\"regret-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\",\"backNum\":2,\"boardVersion\":" + regretVersion + "}");
        Thread.sleep(600);

        assertEquals(invalidBefore + 1, playerA.count("action-invalid"), "跨局的悔棋同意必须被拒绝");
        assertEquals(2, roomOf(newRoomId).getSteps().size(), "上一局的悔棋不能退掉新局的棋子");
        assertEquals("playing", roomOf(newRoomId).getState(), "新局应保持在对局中");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    /**
     * 交叉组合：一方认输、另一方同时同意和棋。
     * 同一局只能结算一次——不能既写一条「认输」又写一条「和棋」。
     */
    @Test
    @Order(21)
    void crossSurrenderAndPeaceWritesSingleRecord() throws Exception {
        String newRoomId = createStartedRoom();
        String colorOfA = roomOf(newRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        int expected = 0;
        for (int i = 0; i < 3; i++) {
            sendMove(black, newRoomId, i, 9, "black", blackId, whiteId, ++expected);
            sendMove(white, newRoomId, i, 10, "white", whiteId, blackId, ++expected);
        }

        int recordsBefore = jdbc.queryForObject("select count(*) from record where player1 = ? and player2 = ?",
                Integer.class, Integer.valueOf(idA), Integer.valueOf(idB));

        // A 先求和，B 收到请求；随后 A 认输、B 同时点「同意和棋」
        int peaceBeforeB = playerB.count("peace");
        playerA.send("{\"type\":\"peace\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\"}");
        assertTrue(playerB.awaitCount("peace", peaceBeforeB + 1, TIMEOUT), "B 应收到和棋请求");
        // 取最后一条：即本次转发的那条，避免读到前面用例残留的旧请求
        long version = JSONUtil.parseObj(lastOfType(playerB, "peace")).getLong("boardVersion");

        playerA.send(surrenderJson(newRoomId, idA, idB));
        playerB.send("{\"type\":\"peace-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idB
                + "\",\"to\":\"" + idA + "\",\"boardVersion\":" + version + "}");
        Thread.sleep(1200);

        int recordsAfter = jdbc.queryForObject("select count(*) from record where player1 = ? and player2 = ?",
                Integer.class, Integer.valueOf(idA), Integer.valueOf(idB));
        assertEquals(recordsBefore + 1, recordsAfter, "认输与和棋交叉只能结算一次，不能同时写两条记录");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    /**
     * 交叉组合：一方认输后，另一方仍点「同意重开」。
     * 重开不能把已经结束的房间强行拉回「对局中」，否则一端以为在开新局、另一端以为已认输。
     */
    @Test
    @Order(22)
    void restartAgreeAfterSurrenderIsRejected() throws Exception {
        String newRoomId = createStartedRoom();
        String colorOfA = roomOf(newRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        int expected = 0;
        for (int i = 0; i < 3; i++) {
            sendMove(black, newRoomId, i, 12, "black", blackId, whiteId, ++expected);
            sendMove(white, newRoomId, i, 13, "white", whiteId, blackId, ++expected);
        }

        playerA.send(surrenderJson(newRoomId, idA, idB));
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline && !"room".equals(roomOf(newRoomId).getState())) {
            Thread.sleep(20);
        }
        assertEquals("room", roomOf(newRoomId).getState(), "认输后房间应回到未开局状态");

        int invalidBefore = playerB.count("action-invalid");
        playerB.send("{\"type\":\"restart-agree\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + idB
                + "\",\"to\":\"" + idA + "\"}");
        Thread.sleep(600);

        assertEquals("room", roomOf(newRoomId).getState(), "认输之后的重开同意不能把房间拉回对局中");
        assertTrue(playerB.count("action-invalid") > invalidBefore, "被拒绝的重开应通知发起方");

        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }

    private String surrenderJson(String targetRoomId, String from, String to) {
        return "{\"type\":\"surrender\",\"roomId\":\"" + targetRoomId + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

private String playJson(int x, int y, String color, String from, String to) {
        return playJson(roomId, x, y, color, from, to);
    }

    private String playJson(String targetRoomId, int x, int y, String color, String from, String to) {
        return "{\"type\":\"play\",\"x\":" + x + ",\"y\":" + y + ",\"color\":\"" + color + "\","
                + "\"roomId\":\"" + targetRoomId + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    /**
     * 新建房间并完成开局（房主 A、房客 B），返回房间号
     */
    private String createStartedRoom() throws Exception {
        int beforeCreate = playerA.count("create-room-success");
        playerA.send("{\"type\":\"create-room\",\"user\":" + userJson(idA) + "}");
        assertTrue(playerA.awaitCount("create-room-success", beforeCreate + 1, TIMEOUT), "创建房间应成功");
        String newRoomId = JSONUtil.parseObj(lastOfType(playerA, "create-room-success"))
                .getJSONObject("content").getStr("id");
        int beforeJoin = playerB.count("join-room-success");
        playerB.send("{\"type\":\"join-room\",\"roomId\":\"" + newRoomId + "\",\"user\":" + userJson(idB) + "}");
assertTrue(playerB.awaitCount("join-room-success", beforeJoin + 1, TIMEOUT), "加入房间应成功");
        // 服务端要求双方都准备后才能开局：第二个人准备即自动开局
        int startA = playerA.count("start-game");
        int startB = playerB.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline
                && (SocketServer.roomMap.get(newRoomId) == null
                || SocketServer.roomMap.get(newRoomId).getColor1() == null)) {
            Thread.sleep(20);
        }
        assertNotNull(SocketServer.roomMap.get(newRoomId), "房间应存在");
        assertNotNull(SocketServer.roomMap.get(newRoomId).getColor1(), "开局后应由服务端记录棋子颜色");
        // 开局消息必须确认到达两端后再交给用例计数：只等服务端状态会让「开局消息比基线晚到」
        // 被算进后续的增量里，导致「只重置一次」这类计数断言偶发多 1
        assertTrue(playerA.awaitCount("start-game", startA + 1, TIMEOUT), "开局消息应到达房主");
        assertTrue(playerB.awaitCount("start-game", startB + 1, TIMEOUT), "开局消息应到达房客");
        return newRoomId;
    }

    /**
     * 发送一步落子并等待服务端记录，保证双方落子的先后顺序
     */
    private void sendMove(TestWsClient mover, String targetRoomId, int x, int y, String color,
                          String from, String to, int expectedSteps) throws Exception {
        mover.send(playJson(targetRoomId, x, y, color, from, to));
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            com.whc.fivechess.websocket.domain.Room room = SocketServer.roomMap.get(targetRoomId);
            if (room == null || room.getSteps() == null || room.getSteps().size() >= expectedSteps) {
                return;
            }
            Thread.sleep(10);
        }
        fail("落子未被服务端记录：房间 " + targetRoomId + " 期望步数 " + expectedSteps);
    }

    private com.whc.fivechess.websocket.domain.Room roomOf(String roomId) {
        return SocketServer.roomMap.get(roomId);
    }

    private String lastOfType(TestWsClient client, String type) {
        List<String> messages = client.ofType(type);
        assertFalse(messages.isEmpty(), "应已收到类型为 " + type + " 的消息");
        return messages.get(messages.size() - 1);
    }

    private Map<String, Object> awaitRecord() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        Map<String, Object> record = null;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select id,player1,player2,name1,name2,start,end,win,steps,data,type from record "
                            + "where player1 = ? and player2 = ? order by id desc limit 1",
                    Integer.valueOf(idA), Integer.valueOf(idB));
            if (!rows.isEmpty()) {
                record = rows.get(0);
                break;
            }
            Thread.sleep(50);
        }
        return record;
    }

    private static void assertNotEquals(Object unexpected, Object actual, String message) {
        assertFalse(unexpected == null ? actual == null : unexpected.equals(actual), message);
    }

    /**
     * 第三轮审查新增：结算写两张表（game 存棋盘、record 存战绩）必须原子。
     * 这里把 recordMapper 换成「insert 必抛异常」的桩，模拟战绩写入失败，
     * 断言 game 也不会留下（事务回滚），避免产生没有任何战绩引用的孤儿棋盘数据。
     */
    @Test
    @Order(99)
    void settlementRollsBackGameWhenRecordInsertFails() throws Exception {
        String newRoomId = createStartedRoom();
        String colorOfA = SocketServer.roomMap.get(newRoomId).getColor1();
        String blackId = "black".equals(colorOfA) ? idA : idB;
        String whiteId = "black".equals(colorOfA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        int expected = 0;
        for (int i = 0; i < 3; i++) {
            sendMove(black, newRoomId, i, 9, "black", blackId, whiteId, ++expected);
            sendMove(white, newRoomId, i, 10, "white", whiteId, blackId, ++expected);
        }

        int gamesBefore = jdbc.queryForObject("select count(*) from game", Integer.class);
        int recordsBefore = jdbc.queryForObject("select count(*) from record", Integer.class);

        java.lang.reflect.Field field = SocketServer.class.getDeclaredField("recordMapper");
        field.setAccessible(true);
        Object original = field.get(null);
        Object failing = java.lang.reflect.Proxy.newProxyInstance(
                SocketFlowTests.class.getClassLoader(),
                new Class[]{com.whc.fivechess.mapper.RecordMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        throw new IllegalStateException("模拟战绩写入失败");
                    }
                    return null;
                });
        try {
            field.set(null, failing);
            int beforeSurrender = black.count("surrender");
            white.send("{\"type\":\"surrender\",\"roomId\":\"" + newRoomId + "\",\"from\":\"" + whiteId
                    + "\",\"to\":\"" + blackId + "\"}");
            assertTrue(black.awaitCount("surrender", beforeSurrender + 1, TIMEOUT), "认输仍应广播给对手");
            Thread.sleep(1200L);
        } finally {
            field.set(null, original);
        }

        int gamesAfter = jdbc.queryForObject("select count(*) from game", Integer.class);
        int recordsAfter = jdbc.queryForObject("select count(*) from record", Integer.class);
        assertEquals(recordsBefore, recordsAfter, "战绩写入失败时不应新增 record");
        assertEquals(gamesBefore, gamesAfter, "战绩写入失败时 game 必须回滚，不能留下孤儿棋盘");

        //收尾：退出房间，避免影响后续用例
        playerA.send("{\"type\":\"quit-room\",\"userId\":\"" + idA + "\"}");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        Thread.sleep(300);
    }
}