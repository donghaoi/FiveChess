package com.whc.fivechess;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.component.ModerationService;
import com.whc.fivechess.support.TestWsClient;
import com.whc.fivechess.util.MyUtil;
import com.whc.fivechess.util.PasswordUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * 「注册记录来源 IP + 封号绑定该 IP + 被封 IP 不能再注册」的端到端验证。
 *
 * <p>要验证的核心不是「在线时能封住」，而是<b>用户已经关掉页面、服务端没有任何会话</b>时，
 * 管理员封号依然能拿到 IP 并堵住注册入口——这正是改造前失效的场景。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegisterIpBanTests {

    private static final long TIMEOUT = 6000L;
    private static final String PASSWORD = "reg123456";
    private static final Random RANDOM = new Random();

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ModerationService moderationService;

    private String adminLogin;
    private boolean createdAdmin;
    private String adminId;
    private String adminToken;
    private TestWsClient adminClient;
    /** 本类创建/尝试创建的账号，用于兜底清理 */
    private final List<String> logins = new ArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        adminLogin = moderationService.getAdminLogin();
        createdAdmin = ensureAdminAccount(adminLogin);
        JSONObject content = login(adminLogin).getJSONObject("content");
        adminId = String.valueOf(content.getInt("id"));
        adminToken = content.getStr("token");
        adminClient = TestWsClient.connectAuthed(port, adminId, adminToken);
        assertNotNull(adminClient.await("auth-success", TIMEOUT), "管理员连接鉴权失败");
    }

    @AfterEach
    void unbanLeftovers() {
        //用例中途失败也必须解除封禁：残留的 banned=1 会让同一 IP 上后续所有注册请求被 403
        for (String login : logins) {
            jdbc.update("update user set banned = 0, ban_time = null where login = ?", login);
        }
    }

    @AfterAll
    void cleanUp() {
        if (adminClient != null) {
            adminClient.close();
        }
        for (String login : logins) {
            jdbc.update("delete from moderation_log where target_login = ?", login);
            jdbc.update("delete from user where login = ?", login);
        }
        if (createdAdmin && adminLogin != null) {
            jdbc.update("delete from user where login = ?", adminLogin);
        }
    }

    @Test
    void banBindsRegisterIpEvenWhenUserIsOfflineAndBlocksThatIpRegistration() throws Exception {
        String acct = account();
        logins.add(acct);

        //1) 注册：故意带一个伪造的 X-Forwarded-For。默认不信任转发头，服务端必须忽略它。
        JSONObject reg = post("/registerPost", registerBody(acct), "203.0.113.7");
        assertEquals(200, reg.getInt("code"), "注册应成功：" + reg);

        Integer id = jdbc.queryForObject("select id from user where login = ?", Integer.class, acct);
        assertNotNull(id);
        assertEquals("127.0.0.1", jdbc.queryForObject("select register_ip from user where id = ?", String.class, id),
                "register_ip 必须是真实来源地址，不能被 X-Forwarded-For 伪造");

        //2) 登录并建立连接后立刻断开，等离线宽限期结束：
        //   此时服务端 sessionMap 已无该用户，任何依赖「在线会话」的取值都拿不到 IP，
        //   若封号仍能绑到 IP，只可能来自注册时落库的那一份。
        JSONObject logged = login(acct);
        String token = logged.getJSONObject("content").getStr("token");
        TestWsClient client = TestWsClient.connectAuthed(port, String.valueOf(id), token);
        assertNotNull(client.await("auth-success", TIMEOUT), "用户连接鉴权失败");
        client.close();
        assertTrue(client.awaitClosed(TIMEOUT), "连接应已断开");
        Thread.sleep(2600L);

        //3) 管理员封号
        adminClient.send("{\"type\":\"admin-ban\",\"targetId\":\"" + id + "\"}");
        String ack = awaitContains(adminClient, "已禁止注册", TIMEOUT);
        assertNotNull(ack, "封号回执应说明 IP 已绑定：" + adminClient.messages());
        assertTrue(ack.contains("127.0.0.1"), "回执应带上绑定的来源 IP：" + ack);
        assertEquals(1, intOf("select banned from user where id = ?", id), "应写入 banned=1");
        assertEquals("127.0.0.1", strOf("select register_ip from user where id = ?", id),
                "封禁判定用的是注册时落库的 IP，用户已离线也照样生效");

        //4) 该 IP 不能再注册
        String blockedAcct = account();
        logins.add(blockedAcct);
        JSONObject blocked = post("/registerPost", registerBody(blockedAcct), null);
        assertEquals(403, blocked.getInt("code"), "被封 IP 注册新账号应被拒绝：" + blocked);
        assertTrue(blocked.getStr("message").contains("封号"), "应给出封号提示：" + blocked);

        //5) 被封账号不能登录
        JSONObject loginBlocked = post("/loginPost", loginBody(acct), null);
        assertEquals(403, loginBlocked.getInt("code"), "被封账号登录应被拒绝：" + loginBlocked);

        //6) 解封后：封禁状态清空、该 IP 恢复可注册（来源记录作为账号历史保留）
        adminClient.send("{\"type\":\"admin-unban\",\"targetId\":\"" + id + "\"}");
        assertNotNull(awaitContains(adminClient, "解除封号", TIMEOUT), "解封应回执管理员");
        waitBanned(id, 0);
        assertEquals("127.0.0.1", strOf("select register_ip from user where id = ?", id),
                "解封不应抹掉注册来源记录");
        String afterAcct = account();
        logins.add(afterAcct);
        JSONObject after = post("/registerPost", registerBody(afterAcct), null);
        assertEquals(200, after.getInt("code"), "解封后该 IP 应能正常注册：" + after);
    }

    @Test
    void legacyAccountWithoutRegisterIpIsBannedButNeverFakesAnIp() throws Exception {
        String acct = account();
        logins.add(acct);
        //模拟改造前注册的老账号：没有 register_ip，且此刻完全离线
        jdbc.update("insert into user(login,name,password,time) values(?,?,?,?)",
                acct, acct, PasswordUtil.hash(PASSWORD), MyUtil.now());
        Integer id = jdbc.queryForObject("select id from user where login = ?", Integer.class, acct);
        assertNotNull(id);
        assertNull(jdbc.queryForObject("select register_ip from user where id = ?", String.class, id),
                "模拟数据应没有注册 IP");

        adminClient.send("{\"type\":\"admin-ban\",\"targetId\":\"" + id + "\"}");
        String ack = awaitContains(adminClient, "未取到该用户的 IP", TIMEOUT);
        assertNotNull(ack, "取不到 IP 时必须明确告知管理员，不能静默成功：" + adminClient.messages());

        assertEquals(1, intOf("select banned from user where id = ?", id), "账号仍应被封");
        assertNull(strOf("select register_ip from user where id = ?", id),
                "取不到 IP 时不能编造或写入任何值");
        assertNull(strOf("select last_ip from user where id = ?", id),
                "取不到 IP 时不能编造或写入任何值");

        //没有任何 IP 绑定时，不能把来源判成被封（尤其不能把 unknown 当作封禁依据）
        assertFalse(moderationService.isBannedIp("unknown"), "unknown 不能命中封禁");
        assertFalse(moderationService.isBannedIp("127.0.0.1"), "未绑定 IP 时不应误封任何来源");
    }

    // ==================== 辅助方法 ====================

    private String account() {
        return "reg" + (100000 + RANDOM.nextInt(900000));
    }

    private String registerBody(String acct) {
        return "{\"id\":\"" + acct + "\",\"name\":\"" + acct + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private String loginBody(String acct) {
        return "{\"login\":\"" + acct + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private JSONObject post(String path, String body, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            headers.add("X-Forwarded-For", forwardedFor);
        }
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return JSONUtil.parseObj(response.getBody());
    }

    private JSONObject login(String acct) {
        JSONObject resp = post("/loginPost", loginBody(acct), null);
        assertEquals(200, resp.getInt("code"), "登录失败：" + resp);
        return resp;
    }

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

    private void waitBanned(Integer userId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        Integer value = null;
        while (System.currentTimeMillis() < deadline) {
            value = jdbc.queryForObject("select banned from user where id = ?", Integer.class, userId);
            if (value != null && value == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, value == null ? -1 : value.intValue(), "封禁状态未在超时内变为期望值");
    }

    private int intOf(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private String strOf(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    /** 准备管理员账号：保留账号无法通过注册接口创建，测试环境直接落库；返回是否由本次创建 */
    private boolean ensureAdminAccount(String login) {
        Integer count = jdbc.queryForObject("select count(*) from user where login = ?", Integer.class, login);
        if (count != null && count > 0) {
            return false;
        }
        jdbc.update("insert into user(login,name,password,time) values(?,?,?,?)",
                login, login, PasswordUtil.hash(PASSWORD), MyUtil.now());
        return true;
    }
}
