package com.whc.fivechess;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线昵称防护回归测试。
 *
 * 背景：昵称会被直接展示在大厅、公共聊天与房间列表里。注册侧已禁止普通用户占用
 * 「管理员 / admin」，但 report-nick 原先只拦「已被其他账号注册的昵称」，
 * 因此下面三种写法都能绕过：普通空格变体、零宽字符变体、换行符。
 * 这些字符串在数据库里查不到同名账号，界面上却与「管理员」几乎无法区分。
 *
 * 本用例锁定修复后的行为：命中保留名 / 不可见字符 / 他人昵称时一律回退为账号真实昵称，
 * 且正常的自定义昵称不受影响（避免把功能改死）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NicknameGuardTests {

    private static final long TIMEOUT = 6000L;
    private static final String PASSWORD = "ng123456";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;

    private String idA;
    private String idB;
    private String nameA;
    private String nameB;
    private String tokenA;
    private TestWsClient clientA;

    private static String sfx() {
        //毫秒时间戳 13 位，取后 6 位做随机后缀
        return String.valueOf(System.currentTimeMillis()).substring(7);
    }

    /** 每次生成一个必然不同、且符合长度限制的临时昵称 */
    private static int nickSeed = 0;

    private static String nextNick(String prefix) {
        nickSeed++;
        return prefix + (System.currentTimeMillis() % 1000) + nickSeed;
    }

    private String post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
        return response.getBody();
    }

    /** 注册并登录，返回登录响应里的 content 对象 */
    private JSONObject registerAndLogin(String login, String name) {
        post("/registerPost", "{\"id\":\"" + login + "\",\"name\":\"" + name + "\",\"password\":\"" + PASSWORD + "\"}");
        String body = post("/loginPost", "{\"login\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        assertNotNull(body, "登录响应不能为空");
        JSONObject json = JSONUtil.parseObj(body);
        assertNotNull(json.getJSONObject("content"), "登录应返回 content：" + body);
        return json.getJSONObject("content");
    }

    @BeforeAll
    void setUp() throws Exception {
        String suffix = sfx();
        idA = "ng" + suffix + "a";
        idB = "ng" + suffix + "b";
        nameA = "甲" + suffix;
        nameB = "乙" + suffix;
        //昵称上限 10 个字符：suffix 为 4 位，加前缀共 5 位，符合限制
        JSONObject a = registerAndLogin(idA, nameA);
        JSONObject b = registerAndLogin(idB, nameB);
        idA = a.getStr("id");
        idB = b.getStr("id");
        nameA = a.getStr("name");
        nameB = b.getStr("name");
        tokenA = a.getStr("token");
        assertNotNull(tokenA, "登录应下发令牌");

        clientA = TestWsClient.connectAuthed(port, idA, tokenA);
        assertNotNull(clientA.await("auth-success", TIMEOUT), "鉴权应成功");
        assertTrue(clientA.awaitCount("online-user-list", 1, TIMEOUT), "鉴权后应下发在线列表");
    }

    @AfterAll
    void tearDown() {
        if (clientA != null) {
            clientA.close();
        }
    }

    /** 取最近一条在线列表里指定用户的昵称；没有列表返回 null */
    private String latestNick(String userId) {
        List<String> lists = clientA.ofType("online-user-list");
        if (lists.isEmpty()) {
            return null;
        }
        String latest = lists.get(lists.size() - 1);
        JSONObject json = JSONUtil.parseObj(latest);
        JSONObject content = json.getJSONObject("content");
        if (content == null) {
            return null;
        }
        JSONArray infos = content.getJSONArray("userInfos");
        if (infos == null) {
            return null;
        }
        for (int i = 0; i < infos.size(); i++) {
            JSONObject info = infos.getJSONObject(i);
            if (info != null && userId.equals(info.getStr("id"))) {
                return info.getStr("nick");
            }
        }
        return null;
    }

    /**
     * 手工做 JSON 字符串转义。
     * 不能直接用 JSONUtil.toJsonStr(String)：它对字符串既不加引号也不转义换行，
     * 拼出来是非法 JSON（含真实换行的用例会被服务端当成坏报文丢弃，测不到昵称校验本身）。
     */
    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 上报昵称。断言不依赖广播次数：并发场景下会有多条列表交错到达，按「第 N 条」判定会误报。
     * 这里直接读服务端内存中的在线昵称（与 SecurityRegressionTests 的做法一致），
     * 它才是大厅、公共聊天真正使用的值。
     */
    private void report(String nick) throws Exception {
        clientA.send("{\"type\":\"report-nick\",\"userId\":\"" + idA + "\",\"nickName\":\""
                + jsonEscape(nick) + "\"}");
    }

    /** 等待在线昵称变为期望值；超时返回 false（调用方负责给出实际值） */
    private boolean awaitNick(String expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(onlineNick())) {
                return true;
            }
            Thread.sleep(20);
        }
        return expected.equals(onlineNick());
    }

    private String onlineNick() {
        return SocketServer.onlineUserNickMap.get(idA);
    }

    /**
     * 通用校验：先改成自定义名（确认改名通道本身可用），再上报目标值。
     * 保护生效时目标值不会被采用，昵称回退为账号真实昵称。
     */
    private void assertGuarded(String evil) throws Exception {
        String custom = nextNick("临");
        report(custom);
        assertTrue(awaitNick(custom, TIMEOUT), "自定义昵称应生效，实际=" + onlineNick());

        report(evil);
        assertTrue(awaitNick(nameA, TIMEOUT),
                "恶意昵称应回退为真实昵称[" + evil + "]，实际=" + onlineNick());
        assertNotEquals(evil, onlineNick(), "不能采用恶意昵称：" + evil);
    }

    @Test
    @Order(1)
    void normalNickStillWorks() throws Exception {
        String custom = nextNick("棋");
        report(custom);
        assertTrue(awaitNick(custom, TIMEOUT), "正常自定义昵称应生效，实际=" + onlineNick());
    }

    @Test
    @Order(2)
    void adminNickExactIsRejected() throws Exception {
        assertGuarded("管理员");
    }

    @Test
    @Order(3)
    void adminNickWithSpaceIsRejected() throws Exception {
        assertGuarded("管理 员");
    }

    @Test
    @Order(4)
    void adminNickWithZeroWidthIsRejected() throws Exception {
        //零宽空格 U+200B：界面上与「管理员」完全一致
        assertGuarded("管理\u200B员");
    }

    @Test
    @Order(5)
    void adminLoginWithZeroWidthIsRejected() throws Exception {
        assertGuarded("adm\u200Bin");
    }

    @Test
    @Order(6)
    void otherUsersNickIsRejected() throws Exception {
        assertGuarded(nameB);
    }

    @Test
    @Order(7)
    void nickWithNewlineIsRejected() throws Exception {
        //换行可以破坏列表展示，也能用来伪造日志行
        assertGuarded("换\n行");
    }

    @Test
    @Order(8)
    void publicChatShowsRealNick() throws Exception {
        //先把昵称改成冒充名（会被回退），再发言，公共聊天里的 senderNick 必须是真实昵称
        String custom = nextNick("聊");
        report(custom);
        assertTrue(awaitNick(custom, TIMEOUT), "自定义昵称应生效，实际=" + onlineNick());
        report("管理\u200B员");
        assertTrue(awaitNick(nameA, TIMEOUT), "冒充昵称应回退，实际=" + onlineNick());

        int before = clientA.count("public-chat-message");
        clientA.send("{\"type\":\"public-chat-message\",\"content\":\"昵称防护测试\"}");
        assertTrue(clientA.awaitCount("public-chat-message", before + 1, TIMEOUT),
                "应收到本次公共聊天广播");
        List<String> all = clientA.ofType("public-chat-message");
        JSONObject json = JSONUtil.parseObj(all.get(all.size() - 1));
        assertEquals(nameA, json.getStr("senderNick"), "公共聊天显示名必须是真实昵称");
    }
}
