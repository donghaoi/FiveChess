package com.whc.fivechess;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.security.AbuseGuard;
import com.whc.fivechess.support.TestWsClient;
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
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滥用防护、聊天记录留存与管理员能力测试。
 * 使用真实数据库；测试账号统一使用 mod 前缀，管理员账号通过属性覆盖为 modadm，测试结束后清理数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModerationTests {

    private static final long TIMEOUT = 4000L;
    private static final String PASSWORD = "mod123456";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AbuseGuard abuseGuard;
    @Autowired
    private com.whc.fivechess.component.ModerationService moderationService;

    /** 管理员账号名：取自配置（Maven 测试下由 surefire 指定），用例据此准备账号 */
    private String adminLogin;
    /** 管理员账号是否由本用例创建（仅自建时才在清理阶段删除，避免影响已有环境） */
    private boolean createdAdmin;

    private static final Random RANDOM = new Random();
    private static final List<String> logins = new ArrayList<>();
    private static final List<String> userIds = new ArrayList<>();

    private static String adminId;
    private static String adminToken;
    private static String targetId;
    private static String targetToken;
    private static String targetAcct;
    private static String otherId;
    private static String otherToken;
    private static String roomId;

    private static TestWsClient targetClient;
    private static TestWsClient adminClient;
    private static TestWsClient otherClient;

    @BeforeAll
    void setUp() throws Exception {
        adminLogin = moderationService.getAdminLogin();
        //管理员账号是系统保留账号，不能通过注册接口创建；测试环境直接落库准备
        createdAdmin = ensureAdminAccount(adminLogin);
        JSONObject adminContent = login(adminLogin).getJSONObject("content");
        adminId = String.valueOf(adminContent.getInt("id"));
        adminToken = adminContent.getStr("token");
        assertTrue(adminContent.getBool("admin"), "登录响应应标记管理员身份");
        userIds.add(adminId);

        targetAcct = "modt" + String.format("%06d", RANDOM.nextInt(1000000));
        if (ensureAccount(targetAcct)) {
            logins.add(targetAcct);
        }
        JSONObject targetContent = login(targetAcct).getJSONObject("content");
        targetId = String.valueOf(targetContent.getInt("id"));
        targetToken = targetContent.getStr("token");
        userIds.add(targetId);

        String otherAcct = "modo" + String.format("%06d", RANDOM.nextInt(1000000));
        if (ensureAccount(otherAcct)) {
            logins.add(otherAcct);
        }
        JSONObject otherContent = login(otherAcct).getJSONObject("content");
        otherId = String.valueOf(otherContent.getInt("id"));
        otherToken = otherContent.getStr("token");
        userIds.add(otherId);

        //连接后必须先完成令牌鉴权，服务端才会处理业务指令
        targetClient = TestWsClient.connectAuthed(port, targetId, targetToken);
        adminClient = TestWsClient.connectAuthed(port, adminId, adminToken);
        otherClient = TestWsClient.connectAuthed(port, otherId, otherToken);
        assertNotNull(targetClient.await("auth-success", TIMEOUT));
        assertNotNull(adminClient.await("auth-success", TIMEOUT));
        assertNotNull(otherClient.await("auth-success", TIMEOUT));
        reportNick(targetClient, targetId, targetAcct);
        reportNick(adminClient, adminId, "管理员");
        reportNick(otherClient, otherId, otherAcct);
    }

    @AfterAll
    void cleanUp() {
        if (targetClient != null) {
            targetClient.close();
        }
        if (adminClient != null) {
            adminClient.close();
        }
        if (otherClient != null) {
            otherClient.close();
        }
        //清理留存的聊天记录
        for (String id : userIds) {
            jdbc.update("delete from chat_public_record where player = ?", Integer.valueOf(id));
            jdbc.update("delete from chat_room_record where player = ?", Integer.valueOf(id));
        }
        for (String login : logins) {
            jdbc.update("delete from moderation_log where target_login = ?", login);
            jdbc.update("delete from user where login = ?", login);
        }
        if (roomId != null) {
            //解散房间的操作记录没有目标账号，按房间号清理
            jdbc.update("delete from moderation_log where action = 'dismiss-room' and detail like ?", "%" + roomId + "%");
        }
        //管理员账号只在由本用例创建时才删除，避免影响已有环境
        if (createdAdmin && adminLogin != null) {
            jdbc.update("delete from user where login = ?", adminLogin);
        }
    }

    // ==================== 1. 滥用防护 ====================

    @Test
    @Order(1)
    void abuseGuardEnforcesAllLimits() {
        //临时开启并调小阈值，直接对组件做单元级验证（不经过真实请求，避免影响其它用例）
        AbuseGuard.setEnforced(true);
        AbuseGuard.setHttpPerIpLimit(3);
        AbuseGuard.setHttpWindowMs(60000L);
        AbuseGuard.setLoginIpLimit(3);
        AbuseGuard.setLoginAccountLimit(2);
        AbuseGuard.setLoginWindowMs(60000L);
        AbuseGuard.setLoginLockThreshold(3);
        AbuseGuard.setLoginLockMs(60000L);
        AbuseGuard.setWsMaxPerIp(2);
        AbuseGuard.setChatPerUserLimit(2);
        AbuseGuard.setChatWindowMs(60000L);
        try {
            // HTTP 总速率：同 IP 第 4 次开始被拒绝
            String ip = "10.9.9.1";
            assertTrue(abuseGuard.allowHttp(ip));
            assertTrue(abuseGuard.allowHttp(ip));
            assertTrue(abuseGuard.allowHttp(ip));
            assertFalse(abuseGuard.allowHttp(ip), "超过单 IP 总速率上限应被拒绝");

            // 登录：同一账号在窗口内达到上限后被拒绝
            String acct = "mod-limit-acct";
            assertNull(abuseGuard.loginBlockReason("10.9.9.2", acct));
            assertNull(abuseGuard.loginBlockReason("10.9.9.2", acct));
            assertNotNull(abuseGuard.loginBlockReason("10.9.9.2", acct), "同账号尝试过于频繁应被拒绝");

            // 登录 IP 维度：同一 IP 达到上限后被拒绝
            assertNull(abuseGuard.loginBlockReason("10.9.9.5", "mod-ip-a"));
            assertNull(abuseGuard.loginBlockReason("10.9.9.5", "mod-ip-b"));
            assertNull(abuseGuard.loginBlockReason("10.9.9.5", "mod-ip-c"));
            assertNotNull(abuseGuard.loginBlockReason("10.9.9.5", "mod-ip-d"), "同 IP 登录过于频繁应被拒绝");

            // 连续失败锁定：达到阈值后即使密码正确也应被锁定
            String lockAcct = "mod-limit-lock";
            for (int i = 0; i < 3; i++) {
                abuseGuard.recordLoginFailure(lockAcct);
            }
            assertTrue(abuseGuard.isAccountLocked(lockAcct), "连续失败达到阈值应锁定账号");
            String blocked = abuseGuard.loginBlockReason("10.9.9.3", lockAcct);
            assertNotNull(blocked, "锁定账号应被拒绝登录");
            assertTrue(blocked.contains("锁定"), "应给出锁定提示：" + blocked);
            abuseGuard.recordLoginSuccess(lockAcct);
            assertFalse(abuseGuard.isAccountLocked(lockAcct), "登录成功后应清除锁定");

            // WebSocket 单 IP 连接数：第 3 个连接被拒绝
            String wsIp = "10.9.9.4";
            assertTrue(abuseGuard.enterWs(wsIp));
            assertTrue(abuseGuard.enterWs(wsIp));
            assertFalse(abuseGuard.enterWs(wsIp), "超过单 IP 连接数上限应被拒绝");
            assertEquals(2, abuseGuard.wsConnectionCount(wsIp), "被拒绝的连接不应计数");
            abuseGuard.leaveWs(wsIp);
            assertTrue(abuseGuard.enterWs(wsIp), "释放后应可以再次连接");

            // 聊天频率：同一用户第 3 条被拒绝
            String chatUser = "mod-limit-chat";
            assertTrue(abuseGuard.allowChat(chatUser));
            assertTrue(abuseGuard.allowChat(chatUser));
            assertFalse(abuseGuard.allowChat(chatUser), "超过聊天频率上限应被拒绝");
        } finally {
            abuseGuard.clearAll();
            //测试环境统一关闭防护：显式恢复为关闭，避免影响后续用例
            AbuseGuard.setEnforced(false);
        }
        assertFalse(AbuseGuard.isEnforced(), "测试环境应保持防护关闭");
    }

    // ==================== 2. 聊天记录留存 ====================

    @Test
    @Order(2)
    void chatRecordsStoreRawContentAndIp() throws Exception {
        //公共聊天：窗口显示过滤后内容，库中保存原文与发言 IP
        String raw = "我要买冰毒";
        targetClient.send("{\"type\":\"public-chat-message\",\"content\":\"" + raw + "\"}");
        String broadcast = targetClient.await("public-chat-message", TIMEOUT);
        assertNotNull(broadcast, "应收到公共聊天广播");
        assertTrue(broadcast.contains("我要买**"), "聊天窗口应展示过滤后的内容：" + broadcast);
        assertFalse(broadcast.contains(raw), "窗口不应出现未过滤的原始内容");
        waitForRow("select count(*) from chat_public_record where player = ? and content = ?",
                Integer.valueOf(targetId), raw);

        //房间聊天：写入房间聊天记录表
        targetClient.send("{\"type\":\"create-room\",\"user\":{\"id\":\"" + targetId + "\",\"name\":\"" + targetAcct + "\"}}");
        String created = targetClient.await("create-room-success", TIMEOUT);
        assertNotNull(created, "应创建房间成功");
        roomId = JSONUtil.parseObj(created).getJSONObject("content").getStr("id");
        targetClient.send("{\"type\":\"chat-message\",\"roomId\":\"" + roomId + "\",\"content\":\"" + raw + "\"}");
        assertNotNull(targetClient.await("chat-message", TIMEOUT), "应收到房间聊天消息");
        waitForRow("select count(*) from chat_room_record where player = ? and room_id = ? and content = ?",
                Integer.valueOf(targetId), roomId, raw);

        //两张表都记录了发言者实时 IP
        Integer roomIpCount = jdbc.queryForObject(
                "select count(*) from chat_room_record where player = ? and ip is not null and ip <> ''",
                Integer.class, Integer.valueOf(targetId));
        assertNotNull(roomIpCount);
        assertTrue(roomIpCount > 0, "房间聊天记录应保存发言者的实时 IP");
        Integer pubIpCount = jdbc.queryForObject(
                "select count(*) from chat_public_record where player = ? and ip is not null and ip <> ''",
                Integer.class, Integer.valueOf(targetId));
        assertNotNull(pubIpCount);
        assertTrue(pubIpCount > 0, "公共聊天记录应保存发言者的实时 IP");
    }

    // ==================== 3. 管理员禁言 ====================

    @Test
    @Order(3)
    void adminMuteThenUnmute() throws Exception {
        //非管理员不能执行禁言
        otherClient.send("{\"type\":\"admin-mute\",\"targetId\":\"" + targetId + "\"}");
        assertNotNull(otherClient.await("admin-fail", TIMEOUT), "非管理员执行管理操作应被拒绝");

        adminClient.send("{\"type\":\"admin-mute\",\"targetId\":\"" + targetId + "\"}");
        assertNotNull(targetClient.await("muted", TIMEOUT), "被禁言用户应收到通知");
        assertNotNull(adminClient.await("admin-action-ok", TIMEOUT), "禁言成功应回执管理员");
        waitForInt("select muted from user where id = ?", 1, Integer.valueOf(targetId));

        //被禁言后发言被拒绝
        targetClient.send("{\"type\":\"chat-message\",\"roomId\":\"" + roomId + "\",\"content\":\"禁言后发言\"}");
        assertNotNull(targetClient.await("muted", TIMEOUT), "被禁言用户发言应被拒绝");

        //解除禁言后恢复发言
        adminClient.send("{\"type\":\"admin-unmute\",\"targetId\":\"" + targetId + "\"}");
        assertNotNull(targetClient.await("unmuted", TIMEOUT), "解除禁言应通知用户");
        waitForInt("select muted from user where id = ?", 0, Integer.valueOf(targetId));
        targetClient.send("{\"type\":\"chat-message\",\"roomId\":\"" + roomId + "\",\"content\":\"解禁后发言\"}");
        assertNotNull(targetClient.await("chat-message", TIMEOUT), "解除禁言后应能正常发言");
    }

    // ==================== 4. 管理员隐身查看房间与解散房间 ====================

    @Test
    @Order(4)
    void adminInvisibleViewAndDismissRoom() throws Exception {
        adminClient.send("{\"type\":\"admin-enter-room\",\"roomId\":\"" + roomId + "\"}");
        String view = adminClient.await("admin-room-view", TIMEOUT);
        assertNotNull(view, "管理员应能进入任意房间查看");
        JSONObject viewJson = JSONUtil.parseObj(view);
        assertEquals(roomId, viewJson.getStr("roomId"));
        assertEquals(targetAcct, viewJson.getJSONObject("roomView").getStr("playerName1"), "应能看到房间人员");
        assertEquals(0, viewJson.getJSONObject("roomView").getInt("spectatorCount"), "隐身查看不计入观战人数");
        JSONArray records = viewJson.getJSONArray("chatRecords");
        assertNotNull(records);
        //管理员的房间聊天窗口与普通成员一致：展示过滤后的内容（原始内容只留在数据库，见 chatRecordsStoreRawContentAndIp）
        boolean foundMasked = false;
        for (int i = 0; i < records.size(); i++) {
            if (records.getJSONObject(i).getStr("content").contains("我要买**")) {
                foundMasked = true;
                break;
            }
        }
        assertTrue(foundMasked, "管理员应能看到该房间的聊天记录（展示过滤后的内容）");

        //房内用户不应感知管理员进入
        long deadline = System.currentTimeMillis() + 1200;
        while (System.currentTimeMillis() < deadline) {
            for (String message : targetClient.messages()) {
                assertFalse(message.contains("admin-"), "房内用户不应收到管理员相关消息：" + message);
                assertFalse(message.contains("隐身"), "房内用户不应感知管理员进入：" + message);
            }
            Thread.sleep(50);
        }

        //实时房间消息也应同步给隐身查看的管理员
        targetClient.send("{\"type\":\"chat-message\",\"roomId\":\"" + roomId + "\",\"content\":\"隐身可见消息\"}");
        boolean observerGot = false;
        deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline && !observerGot) {
            for (String message : adminClient.messages()) {
                if (message.contains("隐身可见消息")) {
                    observerGot = true;
                    break;
                }
            }
            Thread.sleep(50);
        }
        assertTrue(observerGot, "管理员应实时看到房间消息");

        //解散房间：房内用户收到提示，管理员收到回执
        adminClient.send("{\"type\":\"admin-dismiss-room\",\"roomId\":\"" + roomId + "\"}");
        String closed = targetClient.await("room-closed", TIMEOUT);
        assertNotNull(closed, "房间被解散时房内用户应收到通知");
        assertTrue(closed.contains("管理员"), "提示应说明是管理员解散：" + closed);
        assertNotNull(adminClient.await("admin-action-ok", TIMEOUT), "解散成功应回执管理员");
    }

    // ==================== 5. 管理员封号与解封 ====================

    @Test
    @Order(5)
    void adminBanBlocksLoginThenUnbanRestores() throws Exception {
        adminClient.send("{\"type\":\"admin-ban\",\"targetId\":\"" + targetId + "\"}");
        assertNotNull(targetClient.await("banned", TIMEOUT), "被封号用户应收到通知");
        assertTrue(targetClient.awaitClosed(TIMEOUT), "被封号后连接应立即断开");

        //封号落库，来源 IP 已存在账号档案里（register_ip / last_ip），无需另存快照
        waitForInt("select banned from user where id = ?", 1, Integer.valueOf(targetId));
        String registerIp = waitForNotNull("select register_ip from user where id = ?", Integer.valueOf(targetId));
        assertNotNull(registerIp, "账号应有注册来源 IP 记录");

        //被封号账号无法登录
        JSONObject loginResp = postRaw("/loginPost", "{\"login\":\"" + targetAcct + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(403, loginResp.getInt("code"), "被封号账号登录应被拒绝：" + loginResp);
        assertTrue(loginResp.getStr("message").contains("您已被管理员封号"), "应给出封号提示：" + loginResp);

        //被封号 IP 无法注册新账号
        String newAcct = "modx" + String.format("%06d", RANDOM.nextInt(1000000));
        JSONObject registerResp = postRaw("/registerPost",
                "{\"id\":\"" + newAcct + "\",\"name\":\"" + newAcct + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(403, registerResp.getInt("code"), "被封号 IP 注册应被拒绝：" + registerResp);

        //已封号列表包含该账号，管理员可解封
        adminClient.send("{\"type\":\"admin-banned-list\"}");
        String bannedList = adminClient.await("admin-banned-list", TIMEOUT);
        assertNotNull(bannedList, "应返回已封号账号列表");
        assertTrue(bannedList.contains(targetAcct), "已封号列表应包含目标账号：" + bannedList);

        adminClient.send("{\"type\":\"admin-unban\",\"targetId\":\"" + targetId + "\"}");
        assertNotNull(awaitContains(adminClient, "解除封号", TIMEOUT), "解封应回执管理员");
        waitForInt("select banned from user where id = ?", 0, Integer.valueOf(targetId));
        assertNull(jdbc.queryForObject("select ban_time from user where id = ?", String.class, Integer.valueOf(targetId)),
                "解封应清除封号时间");
        JSONObject loginAgain = postRaw("/loginPost", "{\"login\":\"" + targetAcct + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, loginAgain.getInt("code"), "解封后应能正常登录：" + loginAgain);
    }

    // ==================== 6. 管理操作记录 ====================

    @Test
    @Order(6)
    void moderationActionsAreLogged() {
        Integer muteCount = jdbc.queryForObject(
                "select count(*) from moderation_log where admin_login = ? and action = 'mute' and target_login = ?",
                Integer.class, adminLogin, targetAcct);
        assertNotNull(muteCount);
        assertTrue(muteCount > 0, "禁言操作应写入操作记录");
        Integer banCount = jdbc.queryForObject(
                "select count(*) from moderation_log where admin_login = ? and action = 'ban' and target_login = ?",
                Integer.class, adminLogin, targetAcct);
        assertNotNull(banCount);
        assertTrue(banCount > 0, "封号操作应写入操作记录");
        String detail = jdbc.queryForObject(
                "select detail from moderation_log where action = 'ban' and target_login = ? order by id desc limit 1",
                String.class, targetAcct);
        assertNotNull(detail);
        assertTrue(detail.contains("IP"), "封号记录应包含绑定 IP：" + detail);
        Integer dismissCount = jdbc.queryForObject(
                "select count(*) from moderation_log where admin_login = ? and action = 'dismiss-room'",
                Integer.class, adminLogin);
        assertNotNull(dismissCount);
        assertTrue(dismissCount > 0, "解散房间应写入操作记录");
    }

    // ==================== 辅助方法 ====================

    /** 准备管理员测试账号：直接写入数据库（注册接口已禁止注册保留账号）；返回是否由本次创建 */
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

    /** 准备测试账号；返回是否由本次创建（账号已存在时返回 false） */
    private boolean ensureAccount(String login) {
        JSONObject register = postRaw("/registerPost",
                "{\"id\":\"" + login + "\",\"name\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        int code = register.getInt("code");
        //账号已存在时返回非 200，属于正常情况（例如本地库中已创建过管理员）
        assertTrue(code == 200 || register.getStr("message").contains("已被注册"),
                "账号准备失败：" + register);
        return code == 200;
    }

    private JSONObject login(String login) {
        JSONObject loginResp = postRaw("/loginPost", "{\"login\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, loginResp.getInt("code"), "登录失败：" + loginResp);
        return loginResp;
    }

    private void reportNick(TestWsClient client, String userId, String nick) throws Exception {
        client.send("{\"type\":\"report-nick\",\"userId\":\"" + userId + "\",\"nickName\":\"" + nick + "\"}");
        Thread.sleep(200);
    }

    /** 轮询等待某个整型字段变为期望值（异步写入 + 消息回执存在时序，必须轮询而不是立即断言） */
    private void waitForInt(String sql, int expected, Object... args) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        Integer value = null;
        while (System.currentTimeMillis() < deadline) {
            value = jdbc.queryForObject(sql, Integer.class, args);
            if (value != null && value == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, value == null ? -1 : value.intValue(), "字段未在超时内变为期望值：" + sql);
    }

    /** 轮询等待某个字段被写入（非空） */
    private String waitForNotNull(String sql, Object... args) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        String value = null;
        while (System.currentTimeMillis() < deadline) {
            value = jdbc.queryForObject(sql, String.class, args);
            if (value != null && !value.isEmpty()) {
                return value;
            }
            Thread.sleep(50);
        }
        assertNotNull(value, "字段未在超时内写入：" + sql);
        return value;
    }

    /** 等待客户端收到包含指定关键字的消息（避免命中此前的同类消息） */
    private String awaitContains(TestWsClient client, String keyword, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (String message : client.messages()) {
                if (message.contains(keyword)) {
                    return message;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    private void waitForRow(String sql, Object... args) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbc.queryForObject(sql, Integer.class, args);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(50);
        }
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        assertTrue(count != null && count > 0, "未在超时内写入聊天记录：" + sql);
    }

    private JSONObject postRaw(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return JSONUtil.parseObj(response.getBody());
    }
}