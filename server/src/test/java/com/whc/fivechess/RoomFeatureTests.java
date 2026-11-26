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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 覆盖邀请、观战、公开/私人房间、房主踢人与在线状态。
 * 使用真实数据库，测试账号统一使用 rf 前缀，测试结束后清理测试数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoomFeatureTests {

    private static final long TIMEOUT = 4000L;
    private static final Random RANDOM = new Random();

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private static final List<String> logins = new ArrayList<>();
    private static final List<String> gameIds = new ArrayList<>();
    private static final Map<String, String> nameById = new HashMap<>();
    private static final Map<String, String> tokenById = new HashMap<>();

    private static TestWsClient playerA;
    private static TestWsClient playerB;
    private static TestWsClient playerC;
    private static TestWsClient playerD;

    private static String idA;
    private static String idB;
    private static String idC;
    private static String idD;

    private static String nickA;
    private static String nickB;
    private static String nickC;
    private static String nickD;

    @BeforeAll
    void setUp() throws Exception {
        idA = registerAndLogin("rfa");
        idB = registerAndLogin("rfb");
        idC = registerAndLogin("rfc");
        idD = registerAndLogin("rfd");
        playerA = TestWsClient.connectAuthed(port, idA, tokenById.get(idA));
        playerB = TestWsClient.connectAuthed(port, idB, tokenById.get(idB));
        playerC = TestWsClient.connectAuthed(port, idC, tokenById.get(idC));
        playerD = TestWsClient.connectAuthed(port, idD, tokenById.get(idD));
        assertNotNull(playerA.await("auth-success", TIMEOUT), "有效令牌应鉴权成功");
        String suffix = String.format("%04d", RANDOM.nextInt(10000));
        nickA = "邀甲" + suffix;
        nickB = "邀乙" + suffix;
        nickC = "邀丙" + suffix;
        nickD = "邀丁" + suffix;
        reportNick(playerA, idA, nickA);
        reportNick(playerB, idB, nickB);
        reportNick(playerC, idC, nickC);
        reportNick(playerD, idD, nickD);
        assertNotNull(awaitUserInfo(playerA, nickD), "四名测试用户应都出现在在线列表");
        //默认关闭拒绝冷却：冷却行为由专用用例验证，避免干扰其它用例的再次邀请
        SocketServer.setRejectCooldownMs(0L);
        SocketServer.clearInviteBlocks();
    }

    @Test
    @Order(1)
    void inviteGuardsAndInviteSent() throws Exception {
        //不在房间中不能邀请他人
        playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idB + "\"}");
        assertTrue(playerA.awaitCount("invite-fail", 1, TIMEOUT), "不在房间中发起邀请应收到失败提示");
        assertTrue(lastOfType(playerA, "invite-fail").contains("只有在房间中"), "应提示仅在房间内可邀请");

        //A 建一个空房（房间中），C 与 D 同房
        String roomA = createRoom(playerA, idA);
        String roomC = createRoom(playerC, idC);
        joinRoom(playerD, idD, roomC);
        //双方已坐下但都未准备：状态为「房间中」
        assertEquals("inRoom", onlineStatusOf(playerA, nickC), "同房但未准备时状态应为房间中");
        assertEquals("inRoom", onlineStatusOf(playerA, nickA), "独自在房内状态应为房间中");
        assertEquals("idle", onlineStatusOf(playerA, nickB), "未进房的用户状态应为空闲");

        //双方都准备后才算「游戏中」
        playerC.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idC + "\",\"to\":\"" + idD + "\"}");
        playerD.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idD + "\",\"to\":\"" + idC + "\"}");
        long roomCDeadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < roomCDeadline
                && !"inGame".equals(onlineStatusOf(playerA, nickC))) {
            Thread.sleep(100);
        }
        assertEquals("inGame", onlineStatusOf(playerA, nickC), "双方准备后状态应为游戏中");

        //游戏中的用户不可邀请
        int failBefore = playerA.count("invite-fail");
        playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idC + "\"}");
        assertTrue(playerA.awaitCount("invite-fail", failBefore + 1, TIMEOUT), "邀请游戏中的用户应被拒");
        assertTrue(lastOfType(playerA, "invite-fail").contains("正在游戏中"), "应提示对方正在游戏中");

        //邀请空闲用户可以成功，双方各收到对应消息
        int sentBefore = playerA.count("invite-sent");
        int receivedBefore = playerB.count("invite-received");
        playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idB + "\"}");
        assertTrue(playerA.awaitCount("invite-sent", sentBefore + 1, TIMEOUT), "邀请者应收到已发送回执");
        assertTrue(playerB.awaitCount("invite-received", receivedBefore + 1, TIMEOUT), "被邀请者应收到邀请");
        JSONObject sent = JSONUtil.parseObj(lastOfType(playerA, "invite-sent"));
        assertEquals(roomA, sent.getStr("roomId"));
        assertEquals(nickB, sent.getStr("targetNick"));
        JSONObject received = JSONUtil.parseObj(lastOfType(playerB, "invite-received"));
        assertEquals(roomA, received.getStr("roomId"), "邀请应指向邀请者的房间");
        assertEquals(idA, received.getStr("fromId"), "邀请应带上邀请者id");
        assertEquals(nickA, received.getStr("fromName"), "邀请应带上邀请者昵称");
        assertTrue(SocketServer.roomMap.get(roomA).getInvitedIds().contains(idB), "受邀用户应记录在房间的邀请名单");

        leaveAll();
    }

    @Test
    @Order(2)
    void inviteAcceptJoinsRoom() throws Exception {
        String roomA = createRoom(playerA, idA);
        int receivedBefore = playerB.count("invite-received");
        playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idB + "\"}");
        assertTrue(playerB.awaitCount("invite-received", receivedBefore + 1, TIMEOUT), "被邀请者应收到邀请");

        int joinSuccessBefore = playerB.count("join-room-success");
        int joinNoticeBefore = playerA.count("someone-join-room");
        playerB.send("{\"type\":\"invite-accept\",\"roomId\":\"" + roomA + "\",\"fromId\":\"" + idA + "\"}");
        assertTrue(playerB.awaitCount("join-room-success", joinSuccessBefore + 1, TIMEOUT), "接受邀请后应进入房间");
        JSONObject joinSuccess = JSONUtil.parseObj(lastOfType(playerB, "join-room-success"));
        assertEquals(roomA, joinSuccess.getJSONObject("content").getStr("id"));
        assertEquals(idB, joinSuccess.getJSONObject("content").getStr("playerId2"));
        assertTrue(playerA.awaitCount("someone-join-room", joinNoticeBefore + 1, TIMEOUT), "房主应收到有人加入通知");
        JSONObject accepted = JSONUtil.parseObj(playerA.await("invite-accepted", TIMEOUT));
        assertNotNull(accepted, "邀请者应收到对方已接受");
        assertEquals(idB, accepted.getStr("byId"));
        assertEquals(nickB, accepted.getStr("byName"));
        assertEquals(roomA, SocketServer.playerRoomMap.get(idB), "受邀者应关联到该房间");
        assertFalse(SocketServer.roomMap.get(roomA).getInvitedIds().contains(idB), "接受后应消耗邀请标记");
//双方坐下但未准备，状态为「房间中」
        assertEquals("inRoom", onlineStatusOf(playerA, nickB), "加入后未准备时状态应为房间中");
        assertEquals("room", SocketServer.roomMap.get(roomA).getState(),
                "两人都在但都未准备时，房间对外状态应为「房间中」而不是「游戏中」");
        //只有一方准备时仍为「房间中」
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        long oneReadyDeadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < oneReadyDeadline
                && !SocketServer.roomMap.get(roomA).isReadyId1()) {
            Thread.sleep(50);
        }
        assertEquals("room", SocketServer.roomMap.get(roomA).getState(),
                "仅一方准备时房间状态应保持「房间中」");
        assertEquals("inRoom", onlineStatusOf(playerA, nickB), "仅一方准备时在线状态应保持「房间中」");
        //双方都准备后变为「游戏中」
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        long readyDeadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < readyDeadline
                && !"inGame".equals(onlineStatusOf(playerA, nickB))) {
            Thread.sleep(100);
        }
        assertEquals("inGame", onlineStatusOf(playerA, nickB),
                "双方准备后状态应为游戏中；房间状态=" + SocketServer.roomMap.get(roomA).getState()
                        + " ready1=" + SocketServer.roomMap.get(roomA).isReadyId1()
                        + " ready2=" + SocketServer.roomMap.get(roomA).isReadyId2()
+ " playerRoomA=" + SocketServer.playerRoomMap.get(idA)
                        + " playerRoomB=" + SocketServer.playerRoomMap.get(idB));
        assertEquals("playing", SocketServer.roomMap.get(roomA).getState(),
                "双方都准备后房间状态应为「游戏中」");

        leaveAll();
    }

    @Test
    @Order(3)
    void inviteRejectNotifiesInviter() throws Exception {
        String roomA = createRoom(playerA, idA);
        int receivedBefore = playerB.count("invite-received");
        playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idB + "\"}");
        assertTrue(playerB.awaitCount("invite-received", receivedBefore + 1, TIMEOUT), "被邀请者应收到邀请");

        int rejectedBefore = playerA.count("invite-rejected");
        playerB.send("{\"type\":\"invite-reject\",\"roomId\":\"" + roomA + "\",\"fromId\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("invite-rejected", rejectedBefore + 1, TIMEOUT), "拒绝后邀请者应收到提示");
        JSONObject rejected = JSONUtil.parseObj(lastOfType(playerA, "invite-rejected"));
        assertEquals(idB, rejected.getStr("byId"));
        assertEquals(nickB, rejected.getStr("byName"));
        assertNull(SocketServer.playerRoomMap.get(idB), "拒绝后不应进入房间");
        assertFalse(SocketServer.roomMap.get(roomA).getInvitedIds().contains(idB), "拒绝后应清除邀请标记");
        assertEquals("idle", onlineStatusOf(playerA, nickB), "拒绝后仍应为空闲");

        leaveAll();
    }

    @Test
    @Order(4)
    void spectateIsReadOnlyAndDoesNotAffectResult() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        // 服务端要求双方都准备后才能开局：双方都准备后由服务端直接自动开局
        int startBefore = playerA.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("start-game", startBefore + 1, TIMEOUT), "双方准备后应自动开始对局");

        //D 观战 A（房内已有对手，属于游戏中）
        int successBefore = playerD.count("spectate-success");
        playerD.send("{\"type\":\"spectate\",\"targetId\":\"" + idA + "\"}");
        assertTrue(playerD.awaitCount("spectate-success", successBefore + 1, TIMEOUT), "观战应成功");
        JSONObject success = JSONUtil.parseObj(lastOfType(playerD, "spectate-success"));
        assertEquals(roomId, success.getJSONObject("roomView").getStr("id"));
        assertEquals(1, success.getJSONObject("roomView").getInt("spectatorCount"), "房间应记录 1 名观战者");
        //观战者拿到的房间快照必须包含双方准备状态，供昵称旁展示
        assertTrue(success.getJSONObject("roomView").getBool("readyId1"), "观战视图应包含房主准备状态");
        assertTrue(success.getJSONObject("roomView").getBool("readyId2"), "观战视图应包含房客准备状态");
        assertEquals(roomId, SocketServer.playerRoomMap.get(idD), "观战者应关联到该房间");
        assertEquals("spectating", onlineStatusOf(playerD, nickD), "观战者状态为观战中");

        Room room = SocketServer.roomMap.get(roomId);
        String blackId = "black".equals(room.getColor1()) ? idA : idB;
        String whiteId = blackId.equals(idA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        //观战者发落子不生效（不属于房间成员）
        playerD.send(playJson(roomId, 0, 0, "white", idD, idA));
        Thread.sleep(300);
        assertEquals(0, SocketServer.roomMap.get(roomId).getSteps().size(), "观战者落子不应写入棋局");

        //玩家落子观战者同步；连成五子后观战者同步收到结果（获胜那一步只通过 game-over 通知）
        int playBefore = playerD.count("play");
        int overBefore = playerD.count("game-over");
        int expected = 0;
        for (int i = 0; i < 5; i++) {
            sendMove(black, roomId, i, 0, "black", blackId, whiteId, ++expected);
            if (i < 4) {
                sendMove(white, roomId, i, 10, "white", whiteId, blackId, ++expected);
            }
        }
        assertTrue(playerD.awaitCount("play", playBefore + 8, TIMEOUT), "观战者应同步收到落子");
        assertTrue(playerD.awaitCount("game-over", overBefore + 1, TIMEOUT), "观战者应同步收到对局结果");
        JSONObject over = JSONUtil.parseObj(lastOfType(playerD, "game-over"));
        assertEquals(Integer.valueOf(blackId), over.getInt("winId"), "获胜方应为黑棋");
        assertEquals("over", SocketServer.roomMap.get(roomId).getState(), "有观战者时胜负判定照常完成");

        //观战者退出，观战人数归零
        int updateBefore = playerA.count("spectator-update");
        playerD.send("{\"type\":\"quit-room\"}");
        assertTrue(playerA.awaitCount("spectator-update", updateBefore + 1, TIMEOUT), "退出观战应通知房间");
        assertEquals(0, JSONUtil.parseObj(lastOfType(playerA, "spectator-update"))
                .getJSONObject("roomView").getInt("spectatorCount"), "退出后观战人数应归零");
        assertNull(SocketServer.playerRoomMap.get(idD), "退出观战后应解除房间关联");

        //已结束的对局仍允许进入观战：前端会提示“对局已结束，是否继续观看”
        int endedBefore = playerC.count("spectate-success");
        playerC.send("{\"type\":\"spectate\",\"targetId\":\"" + idA + "\"}");
        assertTrue(playerC.awaitCount("spectate-success", endedBefore + 1, TIMEOUT), "已结束对局应允许观战");
        JSONObject endedView = JSONUtil.parseObj(lastOfType(playerC, "spectate-success"));
        assertEquals("over", endedView.getJSONObject("roomView").getStr("state"), "观战视图应标记对局已结束");
        assertNotNull(endedView.getJSONArray("steps"), "已结束对局应下发终局棋盘步数");
        playerC.send("{\"type\":\"quit-room\"}");
        Thread.sleep(100);

        leaveAll();
    }

    @Test
    @Order(5)
    void privateRoomRequiresPasswordOrInvite() throws Exception {
        String roomId = createRoom(playerA, idA);
        //未设置密码不能从公开切为私人
        int openFailBefore = playerA.count("room-open-fail");
        playerA.send("{\"type\":\"toggle-room-open\",\"open\":\"false\"}");
        assertTrue(playerA.awaitCount("room-open-fail", openFailBefore + 1, TIMEOUT), "未设置密码应被拒绝");
        assertTrue(lastOfType(playerA, "room-open-fail").contains("密码"), "应提示必须设置密码");
        assertTrue(SocketServer.roomMap.get(roomId).isOpen(), "未设置密码时仍应保持公开");

        //密码过短同样被拒绝
        playerA.send("{\"type\":\"toggle-room-open\",\"open\":\"false\",\"password\":\"123\"}");
        assertTrue(playerA.awaitCount("room-open-fail", openFailBefore + 2, TIMEOUT), "密码过短应被拒绝");
        assertTrue(lastOfType(playerA, "room-open-fail").contains("4-20"), "应提示密码长度");

        //设置合法密码后切为私人房间
        int changedBefore = playerA.count("room-open-changed");
        int listBefore = playerC.count("online-rooms");
        playerA.send("{\"type\":\"toggle-room-open\",\"open\":\"false\",\"password\":\"room-pass-123\"}");
        assertTrue(playerA.awaitCount("room-open-changed", changedBefore + 1, TIMEOUT), "房主切换后应收到结果");
        assertEquals(false, JSONUtil.parseObj(lastOfType(playerA, "room-open-changed"))
                .getJSONObject("roomView").getBool("open"), "房间应变为私人");
        assertFalse(SocketServer.roomMap.get(roomId).isOpen());
        assertNotNull(SocketServer.roomMap.get(roomId).getPrivatePasswordHash(), "应保存密码哈希");

        //私人房间仍出现在所有用户的大厅列表中（仅以 open=false 状态标识区分，不再剔除）
        assertTrue(playerC.awaitCount("online-rooms", listBefore + 1, TIMEOUT), "切换后应广播房间列表");
        assertTrue(lastOfType(playerC, "online-rooms").contains(roomId), "私人房间应仍出现在非受邀者的大厅列表");
        assertTrue(lastOfType(playerA, "online-rooms").contains(roomId), "房主应看到自己的私人房间");
        JSONObject listMsg = JSONUtil.parseObj(lastOfType(playerC, "online-rooms"));
        boolean foundInList = false;
        boolean openFlag = true;
        for (int i = 0; i < listMsg.getJSONArray("content").size(); i++) {
            JSONObject r = listMsg.getJSONArray("content").getJSONObject(i);
            if (roomId.equals(r.getStr("id"))) {
                foundInList = true;
                openFlag = r.getBool("open");
            }
        }
        assertTrue(foundInList, "非受邀者大厅列表中应能查到该私人房间");
        assertFalse(openFlag, "大厅列表中该房间应标记为私人（open=false）");

        //未携带密码不能加入私人房间
        int failBefore = playerC.count("join-room-fail");
        playerC.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":" + userJson(idC) + "}");
        assertTrue(playerC.awaitCount("join-room-fail", failBefore + 1, TIMEOUT), "未带密码加入私人房间应被拒");
        assertTrue(lastOfType(playerC, "join-room-fail").contains("密码"), "应提示输入房间密码");
        assertNull(SocketServer.playerRoomMap.get(idC));

        //错误密码不能加入
        playerC.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"password\":\"wrong-pass\","
                + "\"user\":" + userJson(idC) + "}");
        assertTrue(playerC.awaitCount("join-room-fail", failBefore + 2, TIMEOUT), "错误密码应被拒");
        assertTrue(lastOfType(playerC, "join-room-fail").contains("密码错误"), "应提示密码错误");
        assertNull(SocketServer.playerRoomMap.get(idC));

        //正确密码可以加入，且响应中不能出现密码或哈希
        int joinBefore = playerC.count("join-room-success");
        playerC.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"password\":\"room-pass-123\","
                + "\"user\":" + userJson(idC) + "}");
        assertTrue(playerC.awaitCount("join-room-success", joinBefore + 1, TIMEOUT), "正确密码应能加入");
        String joinRaw = lastOfType(playerC, "join-room-success");
        assertFalse(joinRaw.contains("privatePasswordHash"), "响应不得泄露密码哈希");
        assertFalse(joinRaw.contains("room-pass-123"), "响应不得泄露密码明文");
        assertEquals(roomId, SocketServer.playerRoomMap.get(idC), "密码正确用户应关联到房间");
        playerC.send("{\"type\":\"quit-room\"}");
        Thread.sleep(200);

        //受邀用户仍可免密码加入私人房间
        int receivedBefore = playerB.count("invite-received");
        playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idB + "\"}");
        assertTrue(playerB.awaitCount("invite-received", receivedBefore + 1, TIMEOUT), "被邀请者应收到邀请");
        joinRoom(playerB, idB, roomId);
        assertEquals(roomId, SocketServer.playerRoomMap.get(idB), "受邀用户应能免密码加入私人房间");

        //非房主不能切换可见性
        int nonOwnerFailBefore = playerB.count("room-open-fail");
        playerB.send("{\"type\":\"toggle-room-open\",\"open\":\"true\"}");
        assertTrue(playerB.awaitCount("room-open-fail", nonOwnerFailBefore + 1, TIMEOUT), "非房主切换应被拒");
        assertFalse(SocketServer.roomMap.get(roomId).isOpen(), "非房主的切换不应生效");

        //房主切回公开：清除旧密码，之后任意用户可直接加入
        int publicChangedBefore = playerA.count("room-open-changed");
        playerA.send("{\"type\":\"toggle-room-open\",\"open\":\"true\"}");
        assertTrue(playerA.awaitCount("room-open-changed", publicChangedBefore + 1, TIMEOUT), "应能切回公开");
        assertTrue(SocketServer.roomMap.get(roomId).isOpen());
        assertNull(SocketServer.roomMap.get(roomId).getPrivatePasswordHash(), "切回公开应清空密码哈希");
        playerB.send("{\"type\":\"quit-room\"}");
        Thread.sleep(200);
        joinRoom(playerC, idC, roomId);

        leaveAll();
    }

    @Test
    @Order(6)
    void ownerCanKickAndKickedCannotRejoin() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);

        //非房主不能踢人
        int cFailBefore = playerC.count("kick-fail");
        playerC.send("{\"type\":\"kick-player\",\"targetId\":\"" + idB + "\"}");
        assertTrue(playerC.awaitCount("kick-fail", cFailBefore + 1, TIMEOUT), "非房主不能踢人");
        int bFailBefore = playerB.count("kick-fail");
        playerB.send("{\"type\":\"kick-player\",\"targetId\":\"" + idA + "\"}");
        assertTrue(playerB.awaitCount("kick-fail", bFailBefore + 1, TIMEOUT), "房客不能踢房主");

        //房主踢出房客
        int kickedBefore = playerB.count("kicked-from-room");
        int successBefore = playerA.count("kick-success");
        playerA.send("{\"type\":\"kick-player\",\"targetId\":\"" + idB + "\"}");
        assertTrue(playerB.awaitCount("kicked-from-room", kickedBefore + 1, TIMEOUT), "被踢用户应收到提示");
        assertTrue(playerA.awaitCount("kick-success", successBefore + 1, TIMEOUT), "房主应收到踢人成功");
        assertEquals("-1", JSONUtil.parseObj(lastOfType(playerA, "kick-success"))
                .getJSONObject("roomView").getStr("playerId2"), "被踢后房间应回到等待状态");
        assertEquals("waiting", SocketServer.roomMap.get(roomId).getState());
        assertNull(SocketServer.playerRoomMap.get(idB), "被踢用户应解除房间关联");
        assertTrue(SocketServer.roomMap.get(roomId).getKickedIds().contains(idB), "被踢用户应记入黑名单");

        //被踢用户不能再次加入该房间
        int rejoinBefore = playerB.count("join-room-fail");
        playerB.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":" + userJson(idB) + "}");
        assertTrue(playerB.awaitCount("join-room-fail", rejoinBefore + 1, TIMEOUT), "被踢用户再次加入应被拒");
        assertTrue(lastOfType(playerB, "join-room-fail").contains("无法再次加入"), "应提示已被移出");
        assertNull(SocketServer.playerRoomMap.get(idB));

        //其它用户仍然可以加入
        joinRoom(playerD, idD, roomId);
        assertEquals(roomId, SocketServer.playerRoomMap.get(idD), "未被踢的用户可以加入");

        leaveAll();
    }

    @Test
    @Order(7)
    void spectatorStatusIsSpectatingAndInvitable() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        //双方准备并开局后才允许观战（「房间中」不再显示/允许观战）；双方准备后服务端自动开局
        int startBefore7 = playerA.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("start-game", startBefore7 + 1, TIMEOUT), "双方准备后应自动开局");

        //D 观战 A：状态应为“观战中”
        int spectateBefore = playerD.count("spectate-success");
        playerD.send("{\"type\":\"spectate\",\"targetId\":\"" + idA + "\"}");
        assertTrue(playerD.awaitCount("spectate-success", spectateBefore + 1, TIMEOUT), "观战应成功");
        assertEquals("spectating", awaitStatus(playerA, nickD, "spectating"), "观战者状态应为观战中");

        //观战中的用户也可以被邀请
        String roomC = createRoom(playerC, idC);
        int sentBefore = playerC.count("invite-sent");
        int receivedBefore = playerD.count("invite-received");
        playerC.send("{\"type\":\"invite\",\"targetId\":\"" + idD + "\"}");
        assertTrue(playerC.awaitCount("invite-sent", sentBefore + 1, TIMEOUT), "应能邀请观战中的用户");
        assertTrue(playerD.awaitCount("invite-received", receivedBefore + 1, TIMEOUT), "观战者应收到邀请");

        //接受邀请后退出观战并进入邀请者房间
        int joinBefore = playerD.count("join-room-success");
        playerD.send("{\"type\":\"invite-accept\",\"roomId\":\"" + roomC + "\",\"fromId\":\"" + idC + "\"}");
        assertTrue(playerD.awaitCount("join-room-success", joinBefore + 1, TIMEOUT), "接受邀请后应进入房间");
        assertEquals(roomC, SocketServer.playerRoomMap.get(idD), "观战者应转入邀请者房间");
        assertFalse(SocketServer.roomMap.get(roomId).getSpectators().containsKey(idD), "应已退出原观战");
        //转入新房间后双方都未准备，状态应为「房间中」
        assertEquals("inRoom", awaitStatus(playerA, nickD, "inRoom"), "进入房间后未准备时状态应为房间中");

        leaveAll();
    }

    /**
     * 心跳：超过超时未活动的用户移出在线列表，恢复心跳后重新上线
     */
    @Test
    @Order(8)
    void heartbeatTimeoutRemovesAndRestoresOnlineUser() throws Exception {
        try {
            SocketServer.setHeartbeatTimeoutMs(1500L);
            assertNotNull(latestUserInfo(playerA, nickB), "心跳用例开始时应能看到 B 在线");

            boolean removed = false;
            long deadline = System.currentTimeMillis() + 8000L;
            while (System.currentTimeMillis() < deadline) {
                if (latestUserInfo(playerA, nickB) == null) {
                    removed = true;
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue(removed, "超过心跳超时后，B 应从在线列表移除");

            playerB.send("{\"type\":\"heartbeat\",\"userId\":\"" + idB + "\"}");
            boolean restored = false;
            deadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < deadline) {
                if (latestUserInfo(playerA, nickB) != null) {
                    restored = true;
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue(restored, "恢复心跳后，B 应重新出现在在线列表");
        } finally {
            SocketServer.setHeartbeatTimeoutMs(30000L);
        }
    }

    /**
     * 邀请超时：被邀请者超时未处理弹窗时，邀请自动取消并通知双方
     */
    @Test
    @Order(9)
    void inviteTimeoutAutoRejects() throws Exception {
        String roomId = createRoom(playerA, idA);
        try {
            SocketServer.setInviteTimeoutMs(1500L);
            int recvBefore = playerC.count("invite-received");
            playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idC + "\"}");
            assertTrue(playerC.awaitCount("invite-received", recvBefore + 1, TIMEOUT), "C 应收到邀请");

            int rejBefore = playerA.count("invite-rejected");
            assertTrue(playerA.awaitCount("invite-rejected", rejBefore + 1, 8000L), "邀请超时应通知邀请者");
            assertTrue(lastOfType(playerA, "invite-rejected").contains("timeout"), "超时拒绝应标记原因");
            assertTrue(playerC.awaitCount("invite-timeout", 1, 8000L), "C 应收到邀请超时通知");
            assertFalse(SocketServer.roomMap.get(roomId).getInvitedIds().contains(idC), "超时后应清除该房间的受邀标记");
        } finally {
            SocketServer.setInviteTimeoutMs(30000L);
        }
        leaveAll();
    }

    /**
     * 拒绝冷却：被拒绝后 10 秒内邀请者不能再次邀请同一用户
     */
    @Test
    @Order(10)
    void inviteRejectCooldownBlocksReinvite() throws Exception {
        String roomId = createRoom(playerA, idA);
        try {
            SocketServer.setRejectCooldownMs(3000L);
            //清掉此前用例残留的拒绝记录，保证冷却判定只针对本用例
            SocketServer.clearRejectCooldowns();
            int recvBefore = playerC.count("invite-received");
            playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idC + "\"}");
            assertTrue(playerC.awaitCount("invite-received", recvBefore + 1, TIMEOUT), "C 应收到邀请");

            int rejBefore = playerA.count("invite-rejected");
            playerC.send("{\"type\":\"invite-reject\",\"roomId\":\"" + roomId + "\",\"fromId\":\"" + idA + "\"}");
            assertTrue(playerA.awaitCount("invite-rejected", rejBefore + 1, TIMEOUT), "C 拒绝应回执给 A");
            assertTrue(lastOfType(playerA, "invite-rejected").contains("cooldown"), "拒绝回执应带冷却秒数");

            //冷却期内再次邀请被拒，并带剩余秒数与目标 id
            int failBefore = playerA.count("invite-fail");
            playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idC + "\"}");
            assertTrue(playerA.awaitCount("invite-fail", failBefore + 1, TIMEOUT), "冷却期内再次邀请应被拒");
            String fail = lastOfType(playerA, "invite-fail");
            assertTrue(fail.contains("cooldown") && fail.contains(idC), "冷却拒绝应带 targetId 与剩余秒数");

            //冷却结束后可以再次邀请
            Thread.sleep(3200L);
            int sentBefore = playerA.count("invite-sent");
            playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idC + "\"}");
            assertTrue(playerA.awaitCount("invite-sent", sentBefore + 1, TIMEOUT), "冷却结束后应可再次邀请");
        } finally {
            SocketServer.setRejectCooldownMs(0L);
        }
        leaveAll();
    }

    /**
     * 当天屏蔽：选择「拒绝并不再接受该用户邀请」后，该邀请者当天无法再邀请该用户
     */
    @Test
    @Order(11)
    void inviteRejectBlockForToday() throws Exception {
        SocketServer.clearRejectCooldowns();
        SocketServer.clearInviteBlocks();
        String roomId = createRoom(playerA, idA);
        int recvBefore = playerC.count("invite-received");
        playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idC + "\"}");
        assertTrue(playerC.awaitCount("invite-received", recvBefore + 1, TIMEOUT), "C 应收到邀请");
        int rejBefore = playerA.count("invite-rejected");
        playerC.send("{\"type\":\"invite-reject\",\"roomId\":\"" + roomId + "\",\"fromId\":\"" + idA + "\",\"block\":true}");
        assertTrue(playerA.awaitCount("invite-rejected", rejBefore + 1, TIMEOUT), "拒绝并屏蔽应回执给邀请者");
        assertTrue(lastOfType(playerA, "invite-rejected").contains("blocked"), "屏蔽拒绝回执应带 blocked 标记");

        int failBefore = playerA.count("invite-fail");
        playerA.send("{\"type\":\"invite\",\"targetId\":\"" + idC + "\"}");
        assertTrue(playerA.awaitCount("invite-fail", failBefore + 1, TIMEOUT), "当天屏蔽后再次邀请应被拒");
        String fail = lastOfType(playerA, "invite-fail");
        assertTrue(fail.contains("今天") && fail.contains(idC), "应提示当天不再接受并带 targetId");

        //其他邀请者不受该屏蔽影响
        String roomB = createRoom(playerB, idB);
        int recvC = playerC.count("invite-received");
        playerB.send("{\"type\":\"invite\",\"targetId\":\"" + idC + "\"}");
        assertTrue(playerC.awaitCount("invite-received", recvC + 1, TIMEOUT), "其他邀请者不应受该屏蔽影响");

        SocketServer.clearInviteBlocks();
        leaveAll();
    }

    /**
     * 重开局：颜色按对局者身份下发，避免响应方（playerId2）同意重开后双方颜色对调
     */
    @Test
    @Order(12)
    void restartKeepsEachSideOwnColor() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        // 服务端要求双方都准备后才能开局：双方准备后自动开局，服务端按身份下发各自颜色
        int startABefore = playerA.count("start-game");
        int startBBefore = playerB.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("start-game", startABefore + 1, TIMEOUT), "A 应收到开局");
        assertTrue(playerB.awaitCount("start-game", startBBefore + 1, TIMEOUT), "B 应收到开局");
        String colorA = JSONUtil.parseObj(lastOfType(playerA, "start-game")).getStr("content");
        String colorB = JSONUtil.parseObj(lastOfType(playerB, "start-game")).getStr("content");
        assertFalse(colorA.equals(colorB), "开局双方颜色应相反");

        // A 发起重开、B 同意（B 是响应方，也是本房间的 playerId2，正是此前错配的场景）
        int agreeBefore = playerA.count("restart-agree");
        int startBAfter = playerB.count("start-game");
        playerA.send("{\"type\":\"restart\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        //服务端要求「同意」必须对应一条真实的待响应请求，先确认 B 收到请求再同意（真实客户端也是这样）
        int restartReqB = playerB.count("restart");
        assertTrue(playerB.awaitCount("restart", restartReqB + 1, TIMEOUT), "B 应收到重开请求");
        playerB.send("{\"type\":\"restart-agree\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("restart-agree", agreeBefore + 1, TIMEOUT), "发起方应收到重开回执");
        assertTrue(playerB.awaitCount("start-game", startBAfter + 1, TIMEOUT), "响应方应收到新的开局");

        String colorAAfter = JSONUtil.parseObj(lastOfType(playerA, "restart-agree")).getStr("content");
        String colorBAfter = JSONUtil.parseObj(lastOfType(playerB, "start-game")).getStr("content");
        assertFalse(colorAAfter.equals(colorBAfter), "重开后双方颜色应相反");

        // 关键：各自拿到的颜色必须与按身份记录的真实颜色一致
        Room room = SocketServer.roomMap.get(roomId);
        String expectA = idA.equals(room.getPlayerId1()) ? room.getColor1() : room.getColor2();
        String expectB = idB.equals(room.getPlayerId1()) ? room.getColor1() : room.getColor2();
        assertEquals(expectA, colorAAfter, "重开后 A 的颜色应与服务端按其身份记录的一致");
        assertEquals(expectB, colorBAfter, "重开后 B 的颜色应与服务端按其身份记录的一致");

        // 行为验证：白方抢先落子被拒、黑方先手落子被接受并转发给对手
        TestWsClient black = "black".equals(colorAAfter) ? playerA : playerB;
        TestWsClient white = "black".equals(colorAAfter) ? playerB : playerA;
        String blackId = "black".equals(colorAAfter) ? idA : idB;
        String whiteId = "black".equals(colorAAfter) ? idB : idA;

        int rejectBefore = white.count("play-reject");
        white.send(playJson(roomId, 1, 1, "white", whiteId, blackId));
        assertTrue(white.awaitCount("play-reject", rejectBefore + 1, TIMEOUT), "白方抢先落子应被服务端拒绝");

        int playBefore = white.count("play");
        sendMove(black, roomId, 2, 2, "black", blackId, whiteId, 1);
        assertTrue(white.awaitCount("play", playBefore + 1, TIMEOUT), "黑方先手落子应被接受并转发给对手");

        leaveAll();
    }

    /**
     * 观战者能看到双方的操作（悔棋/认输等），并在对局因一方离开而结束时收到结束通知
     */
    @Test
    @Order(13)
    void spectatorSeesNoticesAndSpectateEnds() throws Exception {
String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        //对局开始后才允许观战；双方准备后服务端自动开局
        int startBefore13 = playerA.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("start-game", startBefore13 + 1, TIMEOUT), "双方准备后应自动开局");

        //D 观战 A 所在房间
        int specBefore = playerD.count("spectate-success");
        playerD.send("{\"type\":\"spectate\",\"targetId\":\"" + idA + "\"}");
        assertTrue(playerD.awaitCount("spectate-success", specBefore + 1, TIMEOUT), "观战应成功");

        //A 发起悔棋请求：观战者应看到操作提示
        int noticeBefore = playerD.count("spectate-notice");
        playerA.send("{\"type\":\"regret\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        assertTrue(playerD.awaitCount("spectate-notice", noticeBefore + 1, TIMEOUT), "观战者应看到悔棋请求提示");
        assertTrue(lastOfType(playerD, "spectate-notice").contains("悔棋"), "提示应说明悔棋操作");
        assertTrue(lastOfType(playerD, "spectate-notice").contains(nickA), "提示应带上操作方昵称");

        //A 认输：观战者同样能看到，并显示是谁认输
        playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        assertTrue(playerD.awaitCount("spectate-notice", noticeBefore + 2, TIMEOUT), "观战者应看到认输提示");
        assertTrue(lastOfType(playerD, "spectate-notice").contains("认输"), "提示应说明认输");
        assertTrue(lastOfType(playerD, "spectate-notice").contains(nickA), "认输提示应带上认输方昵称");

//B 准备后离开：观战者昵称旁应显示「已准备」，离开后该状态必须立即回落到「等待加入」
        int updateBefore = playerD.count("spectator-update");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerD.awaitCount("spectator-update", updateBefore + 1, TIMEOUT), "准备状态变化应同步给观战者");
        assertTrue(JSONUtil.parseObj(lastOfType(playerD, "spectator-update"))
                .getJSONObject("roomView").getBool("readyId2"), "离开前观战者应看到 B 已准备");

        //一方离开房间：观战者先收到最新房间视图（离开者不再在房/不再已准备），再收到结束通知
        int endBefore = playerD.count("spectate-end");
        playerB.send("{\"type\":\"quit-room\",\"userId\":\"" + idB + "\"}");
        assertTrue(playerD.awaitCount("spectator-update", updateBefore + 2, TIMEOUT), "一方离开后观战者应收到最新房间视图");
        JSONObject viewAfterLeave = JSONUtil.parseObj(lastOfType(playerD, "spectator-update")).getJSONObject("roomView");
        assertEquals("-1", viewAfterLeave.getStr("playerId2"), "离开者的座位应恢复为空位");
        assertEquals("", viewAfterLeave.getStr("playerName2"), "离开者的昵称应清空");
        assertFalse(viewAfterLeave.getBool("readyId2"), "离开者不应继续显示为已准备");
        assertTrue(playerD.awaitCount("spectate-end", endBefore + 1, TIMEOUT), "一方离开后观战者应收到观战结束通知");
        assertTrue(lastOfType(playerD, "spectate-end").contains("离开"), "结束提示应说明原因");
        assertNotNull(SocketServer.playerRoomMap.get(idD), "结束提示后应保留观战关联，等待用户选择继续观看或离开");

        leaveAll();
    }

    /**
     * 准备后可以取消准备；取消后状态回到「房间中」，对手会收到 false 通知；
     * 双方都准备后服务端立即开局，对局真正开始后不再允许取消准备。
     */
    @Test
    @Order(14)
    void readyCanBeCancelledBeforeStart() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        //只有自己准备（对手还没准备）：此时可以取消
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        long readyDeadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < readyDeadline && !SocketServer.roomMap.get(roomId).isReadyId1()) {
            Thread.sleep(50);
        }
        assertTrue(SocketServer.roomMap.get(roomId).isReadyId1(), "房主准备后服务端应记录为已准备");
        assertEquals("room", SocketServer.roomMap.get(roomId).getState(),
                "仅一方准备时房间状态应保持「房间中」");

        //A 取消准备：B 收到 false，房间与在线状态都回到「房间中」
        int readyMsgBefore = playerB.count("info-ready");
        playerA.send("{\"type\":\"info-ready\",\"content\":false,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        assertTrue(playerB.awaitCount("info-ready", readyMsgBefore + 1, TIMEOUT), "对手应收到取消准备通知");
        assertFalse(JSONUtil.parseObj(lastOfType(playerB, "info-ready")).getBool("content"),
                "取消准备通知的 content 应为 false");
assertFalse(SocketServer.roomMap.get(roomId).isReadyId1(), "房主准备状态应被取消");
        assertEquals("inRoom", onlineStatusOf(playerA, nickA), "取消准备后状态应回到房间中");
        assertEquals("room", SocketServer.roomMap.get(roomId).getState(),
                "取消准备后房间对外状态应回到「房间中」");

        //只有一方准备时不能开局
        int startFailBefore = playerB.count("start-game-fail");
        int startBefore = playerB.count("start-game");
        playerB.send("{\"type\":\"start-game\",\"roomId\":\"" + roomId + "\"}");
        assertTrue(playerB.awaitCount("start-game-fail", startFailBefore + 1, TIMEOUT), "一方未准备时开局应被拒");
        assertEquals(startBefore, playerB.count("start-game"), "不应广播开局");

        //重新准备（另一方也准备）后，服务端自动开局
        int startBefore2 = playerB.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerB.awaitCount("start-game", startBefore2 + 1, TIMEOUT), "双方重新准备后应自动开局");
        assertNotNull(SocketServer.roomMap.get(roomId).getSteps(), "开局后应初始化棋局");

        //对局已开始后取消准备应被服务端忽略，且不转发给对手
        int relayBefore = playerB.count("info-ready");
        playerA.send("{\"type\":\"info-ready\",\"content\":false,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        Thread.sleep(300);
        assertEquals(relayBefore, playerB.count("info-ready"), "对局开始后取消准备不应转发");
        assertTrue(SocketServer.roomMap.get(roomId).isReadyId1(), "对局开始后准备状态不应被撤销");

        leaveAll();
    }

    /**
     * 认输结束本局后：双方准备状态清零、收到 round-reset 通知，
     * 双方再次同时准备即由服务端自动开始下一局（不需要再点「重开」）。
     */
    @Test
    @Order(15)
    void nextRoundStartsByReadyAfterSurrender() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        int startBefore = playerA.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("start-game", startBefore + 1, TIMEOUT), "双方准备后应自动开局");
        Room room = SocketServer.roomMap.get(roomId);
        assertEquals("playing", room.getState());

        //A 认输：本局结束，双方准备状态清零；认输方自己也会收到回合结束通知
        int resetBeforeA = playerA.count("round-reset");
        int resetBeforeB = playerB.count("round-reset");
        playerA.send("{\"type\":\"surrender\",\"roomId\":\"" + roomId
                + "\",\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        assertTrue(playerA.awaitCount("round-reset", resetBeforeA + 1, TIMEOUT),
                "认输方也应收到回合结束通知（否则界面上无法回到准备状态）");
        assertTrue(playerB.awaitCount("round-reset", resetBeforeB + 1, TIMEOUT), "对手应收到回合结束通知");
        assertFalse(room.isReadyId1(), "对局结束后房主应回到未准备");
        assertFalse(room.isReadyId2(), "对局结束后房客应回到未准备");
        assertNull(room.getSteps(), "对局数据应已清空");
        assertEquals("room", room.getState(), "对局结束后房间应回到「房间中」");
        assertEquals("inRoom", onlineStatusOf(playerA, nickA), "对局结束后在线状态应回到房间中");

        //只有一方准备：不开局
        int startAfterSurrender = playerA.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        Thread.sleep(300);
        assertTrue(room.isReadyId1(), "准备状态应被记录");
        assertFalse(room.isReadyId2(), "对手还没准备");
        assertEquals(startAfterSurrender, playerA.count("start-game"), "只有一方准备时不应开局");
        assertEquals("room", room.getState(), "只有一方准备时房间应保持「房间中」");

        //另一方也准备：服务端自动开始下一局
        int startBothBefore = playerB.count("start-game");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerB.awaitCount("start-game", startBothBefore + 1, TIMEOUT), "双方都准备后应自动开局");
        assertTrue(playerA.awaitCount("start-game", startAfterSurrender + 1, TIMEOUT), "双方都准备后应自动开局");
        assertNotNull(room.getSteps(), "新的一局应初始化棋局");
        assertEquals(0, room.getSteps().size(), "新的一局棋盘应为空");
        assertEquals("playing", room.getState(), "新的一局应回到「游戏中」");
        assertTrue(room.isReadyId1() && room.isReadyId2(), "开局后双方准备状态应保持为已准备");

        leaveAll();
    }

    /**
     * 五连结束本局后：同样回到未准备，再次双方准备即开新局，
     * 并且上一局的终局快照（finalSteps）会被清除，不会串到新局。
     */
    @Test
    @Order(16)
    void nextRoundStartsByReadyAfterWin() throws Exception {
        String roomId = createRoom(playerA, idA);
        joinRoom(playerB, idB, roomId);
        int startBefore = playerA.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("start-game", startBefore + 1, TIMEOUT), "双方准备后应自动开局");

        Room room = SocketServer.roomMap.get(roomId);
        String blackId = "black".equals(room.getColor1()) ? idA : idB;
        String whiteId = blackId.equals(idA) ? idB : idA;
        TestWsClient black = blackId.equals(idA) ? playerA : playerB;
        TestWsClient white = whiteId.equals(idA) ? playerA : playerB;

        //黑棋连成五子，本局结束
        int overBefore = black.count("game-over");
        int resetBefore = playerA.count("round-reset");
        int expected = 0;
        for (int i = 0; i < 5; i++) {
            sendMove(black, roomId, i, 0, "black", blackId, whiteId, ++expected);
            if (i < 4) {
                sendMove(white, roomId, i, 10, "white", whiteId, blackId, ++expected);
            }
        }
        assertTrue(black.awaitCount("game-over", overBefore + 1, TIMEOUT), "应收到对局结果");
        assertTrue(playerA.awaitCount("round-reset", resetBefore + 1, TIMEOUT), "对局结束后应收到回合结束通知");
        assertEquals("over", room.getState(), "五连结束后房间状态应为已结束");
        assertFalse(room.isReadyId1(), "对局结束后房主应回到未准备");
        assertFalse(room.isReadyId2(), "对局结束后房客应回到未准备");
        assertNotNull(room.getFinalSteps(), "已结束的对局应保留终局快照供观战");

        //双方再次准备：自动开新局，终局快照被清除
        int startNewBefore = playerA.count("start-game");
        playerA.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        playerB.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(playerA.awaitCount("start-game", startNewBefore + 1, TIMEOUT), "双方再次准备后应自动开始下一局");
        assertEquals("playing", room.getState(), "新的一局应回到「游戏中」");
        assertEquals(0, room.getSteps().size(), "新的一局棋盘应为空");
        assertNull(room.getFinalSteps(), "开新局后上一局的终局快照应被清除");
        assertEquals(1, black.count("game-over") - overBefore, "上一局的结果不应重复下发");

        leaveAll();
    }

    @AfterAll
    void cleanUp() {
        SocketServer.setRejectCooldownMs(10_000L);
        if (playerA != null) playerA.close();
        if (playerB != null) playerB.close();
        if (playerC != null) playerC.close();
        if (playerD != null) playerD.close();
        for (String gameId : gameIds) {
            jdbc.update("delete from record where data = ?", gameId);
            jdbc.update("delete from game where id = ?", gameId);
        }
        //按用户 id 清理本用例产生的对局记录与对局数据
        for (String id : new String[]{idA, idB, idC, idD}) {
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

    private String playJson(String targetRoomId, int x, int y, String color, String from, String to) {
        return "{\"type\":\"play\",\"x\":" + x + ",\"y\":" + y + ",\"color\":\"" + color + "\","
                + "\"roomId\":\"" + targetRoomId + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    private void sendMove(TestWsClient mover, String targetRoomId, int x, int y, String color,
                          String from, String to, int expectedSteps) throws Exception {
        mover.send(playJson(targetRoomId, x, y, color, from, to));
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            Room room = SocketServer.roomMap.get(targetRoomId);
            if (room == null || room.getSteps() == null || room.getSteps().size() >= expectedSteps) {
                return;
            }
            Thread.sleep(10);
        }
        fail("落子未被服务端记录：房间 " + targetRoomId + " 期望步数 " + expectedSteps);
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
        for (TestWsClient client : new TestWsClient[]{playerA, playerB, playerC, playerD}) {
            client.send("{\"type\":\"quit-room\"}");
        }
        Thread.sleep(300);
    }

    private String lastOfType(TestWsClient client, String type) {
        List<String> messages = client.ofType(type);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    private JSONObject findUserInfo(cn.hutool.json.JSONArray infos, String nick) {
        for (int i = 0; i < infos.size(); i++) {
            JSONObject item = infos.getJSONObject(i);
            if (nick.equals(item.getStr("nick"))) {
                return item;
            }
        }
        return null;
    }


    /** 等待最新在线列表中的指定用户达到期望状态（列表广播存在时序，直接读可能拿到旧快照） */
    private String awaitStatus(TestWsClient client, String nick, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        String latest = null;
        while (System.currentTimeMillis() < deadline) {
            JSONObject item = awaitUserInfo(client, nick);
            if (item != null) {
                latest = item.getStr("status");
                if (expected.equals(latest)) {
                    return latest;
                }
            }
            Thread.sleep(50);
        }
        return latest;
    }

    private JSONObject awaitUserInfo(TestWsClient client, String nick) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            List<String> lists = client.ofType("online-user-list");
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

    /**
     * 只看最新一条在线列表里是否包含该昵称（用于心跳下线/恢复判定）
     */
    private JSONObject latestUserInfo(TestWsClient client, String nick) {
        List<String> lists = client.ofType("online-user-list");
        if (lists.isEmpty()) {
            return null;
        }
        cn.hutool.json.JSONArray infos =
                JSONUtil.parseObj(lists.get(lists.size() - 1)).getJSONObject("content").getJSONArray("userInfos");
        if (infos == null) {
            return null;
        }
        return findUserInfo(infos, nick);
    }

    private String onlineStatusOf(TestWsClient client, String nick) throws Exception {
        JSONObject item = awaitUserInfo(client, nick);
        assertNotNull(item, "在线列表应包含 " + nick);
        return item.getStr("status");
    }

    private String registerAndLogin(String prefix) {
        String login = prefix + String.format("%06d", RANDOM.nextInt(1000000));
        logins.add(login);
        post("/registerPost", "{\"id\":\"" + login + "\",\"name\":\"" + login + "\",\"password\":\"rf123456\"}");
        JSONObject loginResponse = post("/loginPost", "{\"login\":\"" + login + "\",\"password\":\"rf123456\"}");
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
}