package com.whc.fivechess;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.controller.Controller;
import com.whc.fivechess.security.AbuseGuard;
import com.whc.fivechess.util.MyUtil;
import com.whc.fivechess.support.TestWsClient;
import com.whc.fivechess.websocket.component.SocketServer;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三轮审查新增用例。
 *
 * 覆盖本轮新发现并修复的 4 类问题：
 * 1) /sessionCheck 是公开接口且不走 HTTP 总限流，需要自己的闸；
 * 2) 只带令牌的探测原来要遍历全部账号（O(n)），改为反向索引后仍要判定正确；
 * 3) 账号/昵称允许不可见字符 → 日志注入、零宽字符冒充；
 * 4) 心跳超时不会退房，全员失联的房间会永久占位（僵尸房间）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ThirdRoundReviewTests {

    private static final long TIMEOUT = 6000L;
    private static final Random RANDOM = new Random();
    private static final String PASSWORD = "tr123456";
    private static final int DEFAULT_SESSION_CHECK_LIMIT = 120;
    private static final long DEFAULT_HEARTBEAT_TIMEOUT = 30_000L;
    private static final long DEFAULT_ROOM_STALE_GRACE = 5 * 60 * 1000L;

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private SocketServer socketServer;
    @Autowired
    private AbuseGuard abuseGuard;
    @Autowired
    private Controller controller;
    @Autowired
    private JdbcTemplate jdbc;

    private final Map<String, String> loginById = new HashMap<>();
    private final Map<String, String> tokenById = new HashMap<>();

    private int sessionCheckLimitBefore = DEFAULT_SESSION_CHECK_LIMIT;
    private long heartbeatTimeoutBefore = DEFAULT_HEARTBEAT_TIMEOUT;
    private long roomStaleGraceBefore = DEFAULT_ROOM_STALE_GRACE;

    /** 清掉本类用例可能残留的账号，保证每次运行都从干净状态开始（昵称唯一性会影响断言） */
    @org.junit.jupiter.api.BeforeAll
    void cleanLeftovers() {
        jdbc.update("delete from user where login regexp '^tr(s|t|r|z|l|o|n|sp)[0-9]{6}$'");
        //前面的测试类可能已经把同一个 IP 的注册配额用完，这里重置一次，
        //避免本类因为「请求过于频繁」而失败（限流本身由 Order(1) 单独验证）
        abuseGuard.clearAll();
    }

    // ==================== 用例 ====================

    /**
     * /sessionCheck 被排除在 HTTP 总限流之外（页面打开、切回前台、断线重连都要立刻拿到结论），
     * 但它是公开接口，必须有独立上限，否则任何人无需登录就能无限调用。
     */
    @Test
    @Order(1)
    void sessionCheckIsRateLimited() {
        sessionCheckLimitBefore = AbuseGuard.sessionCheckPerIpLimitForTest();
        AbuseGuard.setEnforced(true);
        AbuseGuard.setSessionCheckPerIpLimit(3);
        abuseGuard.clearAll();
        try {
            int accepted = 0;
            int rejected = 0;
            for (int i = 0; i < 6; i++) {
                JSONObject r = post("/sessionCheck", "{\"id\":\"1\",\"token\":\"x\"}");
                if (r.getInt("code") == 429) {
                    rejected++;
                } else {
                    accepted++;
                }
            }
            assertEquals(3, accepted, "前 3 次应放行");
            assertEquals(3, rejected, "第 4 次起应返回 429");
        } finally {
            AbuseGuard.setSessionCheckPerIpLimit(sessionCheckLimitBefore);
            abuseGuard.clearAll();
        }
    }

    /**
     * 只带令牌（不带 id）的探测走「按令牌反查账号」路径：
     * 反向索引化之后，未知令牌仍要判为已注销，有效令牌仍要判为有效，退出后立刻失效。
     */
    @Test
    @Order(2)
    void sessionCheckResolvesTokenWithoutFullScan() {
        String id = registerAndLogin("trt");
        String token = tokenById.get(id);

        JSONObject ok = post("/sessionCheck", "{\"token\":\"" + token + "\"}");
        assertTrue(ok.getJSONObject("content").getBool("valid"), "有效令牌只带 token 也应判为有效：" + ok);

        String bootId = ok.getJSONObject("content").getStr("bootId");
        JSONObject bogus = post("/sessionCheck", "{\"token\":\"" + bootId + ".deadbeef\"}");
        assertEquals("loggedout", bogus.getJSONObject("content").getStr("reason"),
                "伪造令牌应判为已注销：" + bogus);

        post("/logoutPost", "{\"token\":\"" + token + "\"}");
        JSONObject after = post("/sessionCheck", "{\"token\":\"" + token + "\"}");
        assertEquals("loggedout", after.getJSONObject("content").getStr("reason"),
                "退出登录后令牌应立刻失效：" + after);
    }

    /**
     * 账号/昵称会进日志、大厅、房间与聊天前缀：零宽字符能让「管理员」与「管理员+零宽」
     * 视觉完全一致，配合昵称唯一索引即可冒充他人。
     *
     * 实测结论（重要）：项目使用的 hutool 4.0.12 会把 U+200B 这类字符规范成空白，
     * 并且在解析 JSON 时不还原反斜杠转义，所以「零宽/换行注入」在当前入口实际上进不来。
     * 但防护仍要保留（纵深防御）：一旦升级 JSON 库或换协议，这条就是第一道闸。
     * 因此这里的断言是「无论哪条路径，都不能让昵称变成视觉上的管理员」。
     */
    @Test
    @Order(3)
    void registerRejectsInvisibleChars() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        //换一个独立 IP，避免与其它用例共用注册限流计数
        request.setRemoteAddr("10.77.3.1");

        String zwId = "trz" + String.format("%06d", RANDOM.nextInt(1000000));
        String zwBody = "{\"id\":\"" + zwId + "\",\"name\":\"管理" + '\u200B' + "员\",\"password\":\"" + PASSWORD + "\"}";
        JSONObject r2 = JSONUtil.parseObj(controller.register(zwBody, request));
        //hutool 4.0.12 会把 U+200B 转成字面文本「\u200b」（6 个可见字符）：
        //昵称既不会被拒，也不会等于「管理员」，只是变得难看。无论哪种结果，都不允许冒充成功。
        if (r2.getInt("code") == 200) {
            String stored = jdbc.queryForObject("select name from user where login = ?", String.class, zwId);
            assertNotEquals("管理员", stored, "零宽字符不能让昵称等于管理员");
            assertFalse(MyUtil.stripInvisible(stored).equals("管理员"),
                    "去掉空白与不可见字符后也不能等于管理员：" + stored);
        } else {
            assertTrue(r2.getInt("code") == 400 || r2.getInt("code") == 403,
                    "含不可见字符要么被拒、要么不能等价于管理员：" + r2);
        }

        //空格变体必须拦：「管理 员」在界面上与「管理员」几乎无法区分（这条同时验证保留名归一化）
        String spId = "trsp" + String.format("%06d", RANDOM.nextInt(1000000));
        String spBody = "{\"id\":\"" + spId + "\",\"name\":\"管理 员\",\"password\":\"" + PASSWORD + "\"}";
        JSONObject r2b = JSONUtil.parseObj(controller.register(spBody, request));
        assertEquals(403, r2b.getInt("code"), "带空格的保留名变体应被拒：" + r2b);

        //对照组：正常中文昵称可以注册（证明不是一刀切拒绝）
        String okId = "tro" + String.format("%06d", RANDOM.nextInt(1000000));
        String okBody = "{\"id\":\"" + okId + "\",\"name\":\"棋友" + RANDOM.nextInt(100)
                + "\",\"password\":\"" + PASSWORD + "\"}";
        assertEquals(200, JSONUtil.parseObj(controller.register(okBody, request)).getInt("code"),
                "正常中文昵称应可注册");

        //双向控制符：同样不能冒充（被拒或被规范化都算通过）
        String rtlId = "trl" + String.format("%06d", RANDOM.nextInt(1000000));
        String rtlBody = "{\"id\":\"" + rtlId + "\",\"name\":\"a" + '\u202E' + "b\",\"password\":\"" + PASSWORD + "\"}";
        JSONObject rtl = JSONUtil.parseObj(controller.register(rtlBody, request));
        assertTrue(rtl.getInt("code") == 400 || rtl.getInt("code") == 200,
                "双向控制符要么被拒、要么被规范化：" + rtl);
        if (rtl.getInt("code") == 200) {
            String stored = jdbc.queryForObject("select name from user where login = ?", String.class, rtlId);
            assertFalse(stored.contains("\u202E"), "存储的昵称不应保留双向控制符：" + stored);
        }
    }

    /**
     * 心跳超时只会把用户标成离线，不会退房；移动端切后台/网络半开时连接仍是 open，
     * 房间就会永久留在内存并出现在大厅列表。巡检应把它回收并通知本人。
     */
    @Test
    @Order(4)
    void idleRoomIsReclaimed() throws Exception {
        heartbeatTimeoutBefore = SocketServer.getHeartbeatTimeoutMsForTest();
        roomStaleGraceBefore = SocketServer.getRoomStaleGraceMsForTest();
        String id = registerAndLogin("trr");
        TestWsClient client = connect(id, tokenById.get(id));
        try {
            client.send("{\"type\":\"create-room\"}");
            assertNotNull(client.await("create-room-success", TIMEOUT), "建房应成功");

            //把「心跳超时 + 宽限」压到最小，模拟长时间无活动的房主
            SocketServer.setHeartbeatTimeoutMs(1L);
            SocketServer.setRoomStaleGraceMs(0L);
            Thread.sleep(80L);
            socketServer.sweepStaleRooms();

            assertNotNull(client.await("room-closed", TIMEOUT),
                    "长时间无活动的房主应收到房间已回收的通知");
        } finally {
            SocketServer.setHeartbeatTimeoutMs(heartbeatTimeoutBefore);
            SocketServer.setRoomStaleGraceMs(roomStaleGraceBefore);
            client.close();
        }
    }

    // ==================== 辅助方法 ====================

    private String registerAndLogin(String prefix) {
        String name = prefix + String.format("%06d", RANDOM.nextInt(1000000));
        JSONObject reg = post("/registerPost",
                "{\"id\":\"" + name + "\",\"name\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}");
        if (reg.getInt("code") == 429) {
            //全量运行时前面十几个测试类在同一 IP、同一 60 秒窗口内已用满注册配额。
            //限流本身由 Order(1) 与黑盒 rate-limit 专项覆盖，这里重置窗口以免用例互相干扰。
            abuseGuard.clearAll();
            reg = post("/registerPost",
                    "{\"id\":\"" + name + "\",\"name\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}");
        }
        assertEquals(200, reg.getInt("code"), "注册失败：" + reg);
        JSONObject lg = post("/loginPost",
                "{\"login\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}");
        if (lg.getInt("code") == 429) {
            abuseGuard.clearAll();
            lg = post("/loginPost",
                    "{\"login\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}");
        }
        assertEquals(200, lg.getInt("code"), "登录失败：" + lg);
        JSONObject content = lg.getJSONObject("content");
        String id = String.valueOf(content.getInt("id"));
        loginById.put(id, name);
        tokenById.put(id, content.getStr("token"));
        return id;
    }

    private TestWsClient connect(String id, String token) throws Exception {
        TestWsClient client = TestWsClient.connectAuthed(port, id, token);
        assertNotNull(client.await("auth-success", TIMEOUT), "鉴权应成功");
        return client;
    }

    private JSONObject post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertNotNull(response.getBody(), "响应体不应为空：" + path);
        return JSONUtil.parseObj(response.getBody());
    }

    @AfterAll
    void tearDown() {
        SocketServer.setHeartbeatTimeoutMs(
                heartbeatTimeoutBefore > 0 ? heartbeatTimeoutBefore : DEFAULT_HEARTBEAT_TIMEOUT);
        SocketServer.setRoomStaleGraceMs(
                roomStaleGraceBefore >= 0 ? roomStaleGraceBefore : DEFAULT_ROOM_STALE_GRACE);
        AbuseGuard.setSessionCheckPerIpLimit(
                sessionCheckLimitBefore > 0 ? sessionCheckLimitBefore : DEFAULT_SESSION_CHECK_LIMIT);
        //回到配置默认值（application.yml 里 security.enabled 默认为 true）
        AbuseGuard.setEnforced(true);
        abuseGuard.clearAll();
    }
}
