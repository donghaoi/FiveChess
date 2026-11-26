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
 * 「封号按注册 IP 与最近登录 IP 判定」的端到端验证。
 *
 * <p>要证明的不是「两个字段都有值」，而是<b>两个出口各自都能独立拦住注册</b>：
 * 用户在家注册、在公司登录，只拦注册 IP 时他换个地方照样注册，必须让登录 IP 也生效。</p>
 *
 * <p>封号不再复制 IP 快照：判定直接读账号自己的 {@code register_ip} 与 {@code last_ip}，
 * 封号后无法登录，这两个字段天然冻结，与「封号当时的快照」等价。</p>
 *
 * <p>测试手段说明：HTTP 请求的来源只能是本机 127.0.0.1，无法真的从两个网段发起，
 * 所以「另一个出口」用直接改库的方式模拟（等价于用户在另一台机器上登录过），
 * 再用真实注册请求验证拦截结果——拦截判定只看库里的值，与这个值是怎么来的无关。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginIpBanTests {

    private static final long TIMEOUT = 6000L;
    private static final String PASSWORD = "lip123456";
    private static final String REMOTE_LOGIN_IP = "203.0.113.20";
    private static final String FAKE_REGISTER_IP = "198.51.100.5";
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
        //封禁判定读的是 register_ip / last_ip，所以残留的 banned=1 会让同一 IP 上
        //后续所有注册请求被 403，必须逐条解除
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

    /** 登录成功才写 last_ip；每次登录覆盖；登录失败不写 */
    @Test
    void loginRecordsAndRefreshesLastIp() {
        String acct = account();
        logins.add(acct);
        register(acct);
        Integer id = idOf(acct);
        assertNull(strOf("select last_ip from user where id = ?", id), "注册不应写 last_ip（它只表示登录行为）");

        login(acct);
        assertEquals("127.0.0.1", strOf("select last_ip from user where id = ?", id),
                "登录成功应记录本次来源 IP");

        //模拟「上次在另一台机器登录过」：把它改成远端地址，再登录一次，必须被覆盖回本次来源
        jdbc.update("update user set last_ip = ? where id = ?", REMOTE_LOGIN_IP, id);
        login(acct);
        assertEquals("127.0.0.1", strOf("select last_ip from user where id = ?", id),
                "每次登录都应覆盖 last_ip，只保留最近一次");

        //密码错误不能写入：否则攻击者可以用别人的账号名把 last_ip 污染成自己的地址
        jdbc.update("update user set last_ip = ? where id = ?", REMOTE_LOGIN_IP, id);
        JSONObject wrong = post("/loginPost", loginBody(acct, "wrong-password"), null);
        assertEquals(401, wrong.getInt("code"), "错误密码应被拒绝：" + wrong);
        assertEquals(REMOTE_LOGIN_IP, strOf("select last_ip from user where id = ?", id),
                "登录失败不得改动 last_ip");
    }

    /** 封号后两个来源 IP 都参与判定；解封只清标记，来源记录作为账号历史保留 */
    @Test
    void banUsesRegisterIpAndLoginIp() throws Exception {
        String acct = account();
        logins.add(acct);
        register(acct);
        Integer id = idOf(acct);
        assertEquals("127.0.0.1", strOf("select register_ip from user where id = ?", id), "注册应记录 register_ip");
        jdbc.update("update user set last_ip = ? where id = ?", REMOTE_LOGIN_IP, id);

        String ack = ban(id);
        assertEquals(1, intOf("select banned from user where id = ?", id));
        assertEquals("127.0.0.1", strOf("select register_ip from user where id = ?", id),
                "封号不得改写注册 IP");
        assertEquals(REMOTE_LOGIN_IP, strOf("select last_ip from user where id = ?", id),
                "封号不得改写登录 IP");

        assertTrue(ack.contains("已禁止注册"), "回执应说明已绑定 IP：" + ack);
        assertTrue(ack.contains("注册IP=127.0.0.1"), "回执应列出注册 IP：" + ack);
        assertTrue(ack.contains("登录IP=" + REMOTE_LOGIN_IP), "回执应列出登录 IP：" + ack);

        //真实注册请求来自 127.0.0.1，命中 register_ip 应被拒
        String blocked = account();
        logins.add(blocked);
        JSONObject resp = post("/registerPost", registerBody(blocked), null);
        assertEquals(403, resp.getInt("code"), "被封的注册 IP 不能再注册：" + resp);

        //解封：清掉 banned 后同一个 IP 立刻恢复可注册
        int from = adminClient.messages().size();
        adminClient.send("{\"type\":\"admin-unban\",\"targetId\":\"" + id + "\"}");
        assertNotNull(awaitNewContains(from, "解除封号"), "解封应回执管理员：" + adminClient.messages());
        waitBanned(id, 0);
        assertEquals("127.0.0.1", strOf("select register_ip from user where id = ?", id),
                "解封不应抹掉来源记录");
        assertNull(strOf("select ban_time from user where id = ?", id), "解封应清空封号时间");

        String after = account();
        logins.add(after);
        JSONObject ok = post("/registerPost", registerBody(after), null);
        assertEquals(200, ok.getInt("code"), "解封后该 IP 应恢复可注册：" + ok);
    }

    /**
     * 关键用例：注册 IP 与登录 IP 不同，且**请求来源只命中登录 IP** 时，
     * 注册仍然必须被拒——这证明 last_ip 真的参与判定，不是只写进库里好看。
     */
    @Test
    void loginIpAloneBlocksRegistrationWhenRegisterIpDiffers() throws Exception {
        String acct = account();
        logins.add(acct);
        register(acct);
        Integer id = idOf(acct);
        //把注册 IP 改成远端（模拟在家注册），登录 IP 留成本机（模拟在公司/本机登录）
        jdbc.update("update user set register_ip = ?, last_ip = '127.0.0.1' where id = ?", FAKE_REGISTER_IP, id);

        ban(id);
        assertEquals(FAKE_REGISTER_IP, strOf("select register_ip from user where id = ?", id));
        assertEquals("127.0.0.1", strOf("select last_ip from user where id = ?", id));

        assertTrue(moderationService.isBannedIp("127.0.0.1"), "登录 IP 必须在封禁集合里");
        assertTrue(moderationService.isBannedIp(FAKE_REGISTER_IP), "注册 IP 必须在封禁集合里");
        assertFalse(moderationService.isBannedIp("192.0.2.99"), "无关地址不能被误封");

        String blocked = account();
        logins.add(blocked);
        JSONObject resp = post("/registerPost", registerBody(blocked), null);
        assertEquals(403, resp.getInt("code"),
                "注册 IP 与来源不同，但来源命中登录 IP，仍必须拒绝注册：" + resp);
    }

    /** 注册与登录是同一个出口时，回执里只列一份，避免出现两个同样的地址 */
    @Test
    void sameAddressIsListedOnlyOnce() throws Exception {
        String acct = account();
        logins.add(acct);
        register(acct);
        Integer id = idOf(acct);
        login(acct); //last_ip = 127.0.0.1，与 register_ip 相同

        String ack = ban(id);
        assertEquals("127.0.0.1", strOf("select register_ip from user where id = ?", id));
        assertEquals("127.0.0.1", strOf("select last_ip from user where id = ?", id),
                "同一出口的两个字段都应保留真值，去重只影响文案");
        assertFalse(ack.contains("登录IP="), "同一出口不应在回执里重复列一遍：" + ack);
    }

    /** 被封账号登录被拒时不能顺手更新 last_ip（否则封号名单里显示的地址会跟着变） */
    @Test
    void bannedAccountLoginDoesNotTouchLastIp() throws Exception {
        String acct = account();
        logins.add(acct);
        register(acct);
        Integer id = idOf(acct);
        login(acct);

        ban(id);
        jdbc.update("update user set last_ip = ? where id = ?", REMOTE_LOGIN_IP, id);
        JSONObject resp = post("/loginPost", loginBody(acct, PASSWORD), null);
        assertEquals(403, resp.getInt("code"), "被封账号登录应被拒绝：" + resp);
        assertEquals(REMOTE_LOGIN_IP, strOf("select last_ip from user where id = ?", id),
                "登录被拒的路径不得改动 last_ip");
    }

    // ==================== 辅助方法 ====================

    private String account() {
        return "lip" + (100000 + RANDOM.nextInt(900000));
    }

    private void register(String acct) {
        JSONObject resp = post("/registerPost", registerBody(acct), null);
        assertEquals(200, resp.getInt("code"), "注册失败：" + resp);
    }

    /**
     * 发起封号并等本次回执。
     *
     * <p>必须按「发消息前的消息条数」定位新回执：管理员连接是全类共用的，
     * 上一个用例的回执还留在列表里，直接搜关键字会读到旧回执而误判成功。</p>
     */
    private String ban(Integer id) throws Exception {
        int from = adminClient.messages().size();
        adminClient.send("{\"type\":\"admin-ban\",\"targetId\":\"" + id + "\"}");
        String ack = awaitNewContains(from, "已封号");
        assertNotNull(ack, "封号未成功：" + adminClient.messages());
        return ack;
    }

    private String registerBody(String acct) {
        return "{\"id\":\"" + acct + "\",\"name\":\"" + acct + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private String loginBody(String acct, String password) {
        return "{\"login\":\"" + acct + "\",\"password\":\"" + password + "\"}";
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
        JSONObject resp = post("/loginPost", loginBody(acct, PASSWORD), null);
        assertEquals(200, resp.getInt("code"), "登录失败：" + resp);
        return resp;
    }

    /** 等待「本次操作之后」新增的消息里出现关键字，避免读到上一个用例的旧回执 */
    private String awaitNewContains(int fromIndex, String keyword) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            List<String> all = adminClient.messages();
            for (int i = fromIndex; i < all.size(); i++) {
                if (all.get(i).contains(keyword)) {
                    return all.get(i);
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

    private Integer idOf(String login) {
        return jdbc.queryForObject("select id from user where login = ?", Integer.class, login);
    }

    private int intOf(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private String strOf(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

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
