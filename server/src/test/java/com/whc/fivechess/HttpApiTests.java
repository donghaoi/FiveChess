package com.whc.fivechess;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.domain.Step;
import com.whc.fivechess.domain.Steps;
import com.whc.fivechess.util.MD5;
import com.whc.fivechess.util.PasswordUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 接口功能测试：注册（含口令加盐哈希）、登录与令牌下发、历史对局分页、对局数据读取与授权。
 * 使用 application.yml 中配置的真实数据库，测试账号统一使用 itu 前缀，
 * 测试结束后删除本次创建的账号、对局记录与对局数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiTests {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String LOGIN = "itu" + String.format("%06d", new Random().nextInt(1000000));
    private static final String LEGACY_LOGIN = "ituleg" + String.format("%05d", new Random().nextInt(100000));
    private static final String PASSWORD = "itu123456";
    private static final String NAME = "接口测试账号";
    private static final int FAKE_OPPONENT = 999999;

    //本类创建的账号与对局数据，测试结束后精确清理
    private static Integer userId;
    private static String token;
    private static final List<String> gameIds = new ArrayList<>();

    private JSONObject post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCodeValue(), "HTTP 状态码应为 200：" + path);
        assertNotNull(response.getBody(), "响应体不应为空：" + path);
        return JSONUtil.parseObj(response.getBody());
    }

    private JSONObject postJson(String path, String body) {
        JSONObject result = post(path, body);
        assertEquals(200, result.getInt("code"), "业务 code 应为 200，实际响应：" + result);
        return result;
    }

    private JSONObject postFail(String path, String body, int expectCode, String expectMessagePart) {
        JSONObject result = post(path, body);
        assertEquals(expectCode, result.getInt("code"), "业务 code 不符，实际响应：" + result);
        assertTrue(result.getStr("message").contains(expectMessagePart),
                "提示信息应包含[" + expectMessagePart + "]，实际：" + result.getStr("message"));
        return result;
    }

    @Test
    @Order(1)
    void registerValidation() {
        //请求体非法
        postFail("/registerPost", "not-a-json", 400, "请求参数格式错误");
        postFail("/registerPost", "{}", 400, "账号不能为空");
        postFail("/registerPost", "{\"id\":\"ab\"}", 400, "密码不能为空");
        postFail("/registerPost", "{\"id\":\"ab\",\"password\":\"123456\"}", 400, "昵称不能为空");
        //长度校验
        postFail("/registerPost", "{\"id\":\"ab\",\"password\":\"123456\",\"name\":\"n\"}", 400, "账号长度不能小于3位");
postFail("/registerPost", "{\"id\":\"abcdefghijklmn\",\"password\":\"123456\",\"name\":\"n\"}",
                400, "账号长度不能超过12位");
        postFail("/registerPost", "{\"id\":\"abcd\",\"password\":\"123456\",\"name\":\"12345678901\"}",
                400, "昵称长度不能超过10位");
        postFail("/registerPost", "{\"id\":\"abcd\",\"password\":\"12345\",\"name\":\"n\"}", 400, "密码长度不能小于6位");
        postFail("/registerPost", "{\"id\":\"abcd\",\"password\":\"" + repeat("a", 51) + "\",\"name\":\"n\"}",
                400, "密码长度不能超过50位");
        //违规词校验：账号/昵称分别提示，且只提示包含违规内容，不回显具体词
        JSONObject badAccount = postFail("/registerPost",
                "{\"id\":\"bad冰毒x\",\"password\":\"123456\",\"name\":\"n\"}",
                400, "账号包含违规内容");
        assertTrue(!badAccount.getStr("message").contains("冰毒"), "账号违规提示不应泄露具体违规词");
        assertTrue(!badAccount.getStr("message").contains("昵称"), "账号违规提示不应出现昵称字样");
        JSONObject badNickname = postFail("/registerPost",
                "{\"id\":\"abcd\",\"password\":\"123456\",\"name\":\"测试冰毒\"}",
                400, "昵称包含违规内容");
        assertTrue(!badNickname.getStr("message").contains("冰毒"), "昵称违规提示不应泄露具体违规词");
        assertTrue(!badNickname.getStr("message").contains("账号"), "昵称违规提示不应出现账号字样");
        //账号与昵称同时命中时按账号优先提示，仍不泄露具体词
        JSONObject badBoth = postFail("/registerPost",
                "{\"id\":\"bad冰毒y\",\"password\":\"123456\",\"name\":\"测试赌博\"}",
                400, "账号包含违规内容");
        assertTrue(!badBoth.getStr("message").contains("冰毒")
                && !badBoth.getStr("message").contains("赌博"), "同时命中时不应泄露任一具体违规词");
    }

    @Test
    @Order(2)
    void registerAndLoginSuccess() {
        JSONObject register = postJson("/registerPost",
                "{\"id\":\"" + LOGIN + "\",\"name\":\"" + NAME + "\",\"password\":\"" + PASSWORD + "\"}");
        assertTrue(register.getStr("content").contains("注册成功"));

        //账号重复注册（业务码 409：401 专指登录态失效，冲突不复用）
        postFail("/registerPost",
                "{\"id\":\"" + LOGIN + "\",\"name\":\"" + NAME + "\",\"password\":\"" + PASSWORD + "\"}",
                409, "账号已被注册");

        //密码错误与账号未注册应返回完全相同的业务码和提示，避免通过响应差异枚举账号
        postFail("/loginPost", "{\"login\":\"" + LOGIN + "\",\"password\":\"wrong-password\"}", 401, "账号或密码错误");
        postFail("/loginPost", "{\"login\":\"itu_no_such_user\",\"password\":\"123456\"}", 401, "账号或密码错误");
        //账号或密码为空
        postFail("/loginPost", "{\"login\":\"\"}", 400, "账户或密码为空");
        //请求体非法
        postFail("/loginPost", "{bad json", 400, "请求参数格式错误");

        JSONObject login = postJson("/loginPost",
                "{\"login\":\"" + LOGIN + "\",\"password\":\"" + PASSWORD + "\"}");
        JSONObject content = login.getJSONObject("content");
        userId = content.getInt("id");
        token = content.getStr("token");
        assertEquals(NAME, content.getStr("name"));
        assertNotNull(token, "登录应下发访问令牌");
        //密码摘要不应下发给客户端
        assertNull(content.get("password"), "登录响应不应包含密码字段");
        //数据库中保存的是 PBKDF2 加盐哈希，而不是无盐 MD5
        String dbPassword = jdbc.queryForObject("select password from user where login = ?", String.class, LOGIN);
        assertNotNull(dbPassword);
        assertTrue(dbPassword.startsWith("pbkdf2$"), "密码应为加盐哈希，实际：" + dbPassword);
        //同一口令每次哈希结果不同（盐值随机）
        String otherHash = PasswordUtil.hash(PASSWORD);
        assertTrue(!dbPassword.equals(otherHash), "加盐哈希结果应具有随机性");
        assertTrue(PasswordUtil.matches(PASSWORD, dbPassword), "加盐哈希应能校验通过原密码");
    }

    @Test
    @Order(3)
    void legacyMd5PasswordUpgradedOnLogin() {
        String legacyHash = MD5.toMD5("legacy123456");
        assertEquals(32, legacyHash.length());
        jdbc.update("insert into user(login,name,password,time) values(?,?,?,?)",
                LEGACY_LOGIN, "历史账号", legacyHash, "2025-01-01 00:00:00");

        JSONObject login = postJson("/loginPost",
                "{\"login\":\"" + LEGACY_LOGIN + "\",\"password\":\"legacy123456\"}");
        assertNotNull(login.getJSONObject("content").getStr("token"), "历史账号登录后同样下发令牌");
        String upgraded = jdbc.queryForObject("select password from user where login = ?", String.class, LEGACY_LOGIN);
        assertTrue(upgraded.startsWith("pbkdf2$"), "历史 MD5 摘要应在登录后升级为加盐哈希，实际：" + upgraded);
        //升级后仍可正常登录
        postJson("/loginPost", "{\"login\":\"" + LEGACY_LOGIN + "\",\"password\":\"legacy123456\"}");
        //错误密码依然被拒绝
        postFail("/loginPost", "{\"login\":\"" + LEGACY_LOGIN + "\",\"password\":\"wrong-password\"}", 401, "账号或密码错误");
    }

    @Test
    @Order(4)
    void pageRequiresValidToken() {
        postFail("/getPage", "{}", 400, "用户id不能为空");
        //缺少令牌与令牌错误都应被拒绝
        postFail("/getPage", "{\"id\":\"" + userId + "\",\"pageNum\":\"1\"}", 401, "登录状态已失效");
        postFail("/getPage", "{\"id\":\"" + userId + "\",\"pageNum\":\"1\",\"token\":\"not-a-token\"}",
                401, "登录状态已失效");
        //令牌与用户不匹配（用别人的 id）同样被拒绝
        postFail("/getPage", "{\"id\":\"" + FAKE_OPPONENT + "\",\"pageNum\":\"1\",\"token\":\"" + token + "\"}",
                401, "登录状态已失效");
        postFail("/getRecords", "{}", 400, "用户id不能为空");
        postFail("/getRecords", "{\"id\":\"" + userId + "\"}", 401, "登录状态已失效");
    }

    @Test
    @Order(5)
    void pageParameterValidation() {
        postFail("/getPage", "{\"id\":\"" + userId + "\",\"pageNum\":\"abc\",\"token\":\"" + token + "\"}",
                400, "页码格式错误");
        postFail("/getPage", "{\"id\":\"" + userId + "\",\"pageNum\":\"0\",\"token\":\"" + token + "\"}",
                400, "页码不能小于1");
        postFail("/getGameData", "{}", 400, "对局id不能为空");
        postFail("/getGameData", "{\"id\":\"" + UUID.randomUUID() + "\"}", 401, "登录状态已失效");
        postFail("/getGameData", "{\"id\":\"" + UUID.randomUUID() + "\",\"token\":\"" + token + "\"}",
                400, "未找到对局");
    }

    @Test
    @Order(6)
    void pageAndHistoryRecords() {
        //空列表：总数为 0 时不应出现“下一页”
        JSONObject emptyPage = postJson("/getPage",
                "{\"id\":\"" + userId + "\",\"pageNum\":\"1\",\"token\":\"" + token + "\"}")
                .getJSONObject("content");
        assertEquals(0, emptyPage.getInt("total"));
        assertEquals(1, emptyPage.getInt("current"));
        assertEquals(10, emptyPage.getInt("size"), "服务端每页 10 条");
        assertEquals(0, emptyPage.getJSONArray("records").size());
        //前端按 total/current/size 计算总页数与“下一页”，这里输出一次响应供核对
        System.out.println("[功能] 空历史分页响应：" + emptyPage);

        //插入 12 条对局记录，验证每页 10 条的分页字段与排序
        for (int i = 0; i < 12; i++) {
            String gameId = UUID.randomUUID().toString();
            gameIds.add(gameId);
            jdbc.update("insert into game(id,data) values(?,?)", gameId, serializeSteps(6));
            //其中一条带上“我获胜”的结果，用于校验 win 字段的类型与取值
            String day = String.format("%02d", i + 1);
            jdbc.update("insert into record(player1,name1,player2,name2,start,end,win,steps,data,type) "
                            + "values(?,?,?,?,?,?,?,?,?,?)",
                    userId, NAME, FAKE_OPPONENT, "分页对手",
                    "2026-01-" + day + " 10:00:00",
                    "2026-01-" + day + " 10:10:00",
                    i == 8 ? userId : FAKE_OPPONENT,
                    6, gameId, "normal");
        }

        JSONObject page1 = postJson("/getPage",
                "{\"id\":\"" + userId + "\",\"pageNum\":\"1\",\"token\":\"" + token + "\"}")
                .getJSONObject("content");
        assertEquals(12, page1.getInt("total"));
        //总页数由前端按 total/size 计算（服务端分页响应不返回 pages 字段）
        JSONArray records1 = page1.getJSONArray("records");
        assertEquals(10, records1.size(), "第 1 页应返回 10 条");
        //排序：start 倒序
        assertTrue(records1.getJSONObject(0).getStr("start")
                .compareTo(records1.getJSONObject(9).getStr("start")) > 0);
        System.out.println("[功能] 历史对局分页首条：" + records1.getJSONObject(0));

        //win 在数据库中为 varchar，读出后应仍是数字，前端用 === 与用户id比较
        JSONObject winRecord = null;
        for (int i = 0; i < records1.size(); i++) {
            JSONObject each = records1.getJSONObject(i);
            //按值比较：Integer 用 == 只在 -128..127 范围内因缓存成立，id 变大后会恒为 false
            if (each.get("win") != null && each.getInt("win").equals(userId)) {
                winRecord = each;
                break;
            }
        }
        assertNotNull(winRecord, "应能读到本方获胜的记录");
        assertTrue(winRecord.get("win") instanceof Number, "win 应序列化为数字，前端按 === 与 user.id 比较");
        assertEquals(userId, winRecord.getInt("win"));

        JSONObject page2 = postJson("/getPage",
                "{\"id\":\"" + userId + "\",\"pageNum\":\"2\",\"token\":\"" + token + "\"}")
                .getJSONObject("content");
        assertEquals(2, page2.getJSONArray("records").size(), "第 2 页应返回 2 条");

        JSONObject all = postJson("/getRecords", "{\"id\":\"" + userId + "\",\"token\":\"" + token + "\"}");
        assertEquals(12, all.getJSONArray("content").size(), "getRecords 应返回全部 12 条");
    }

    @Test
    @Order(7)
    void gameDataRoundTripAndAuthorization() {
        String gameId = UUID.randomUUID().toString();
        gameIds.add(gameId);
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(1, 3, 4, "black"));
        steps.add(new Step(2, 5, 6, "white"));
        jdbc.update("insert into game(id,data) values(?,?)", gameId, serializeSteps(steps));
        //对局记录决定了对局的归属，只有该局双方可以查看
        jdbc.update("insert into record(player1,name1,player2,name2,start,end,win,steps,data,type) "
                        + "values(?,?,?,?,?,?,?,?,?,?)",
                userId, NAME, FAKE_OPPONENT, "对手甲", "2026-02-01 10:00:00", "2026-02-01 10:10:00",
                userId, 2, gameId, "normal");

        JSONObject result = postJson("/getGameData",
                "{\"id\":\"" + gameId + "\",\"token\":\"" + token + "\"}");
        JSONArray readSteps = result.getJSONObject("content").getJSONArray("steps");
        assertEquals(2, readSteps.size(), "读取到的步数应与写入一致");
        assertEquals(3, readSteps.getJSONObject(0).getInt("x"));
        assertEquals("black", readSteps.getJSONObject(0).getStr("color"));
        assertEquals(2, readSteps.getJSONObject(1).getInt("player"));

        //不属于自己的对局不允许查看
        String otherGameId = UUID.randomUUID().toString();
        gameIds.add(otherGameId);
        jdbc.update("insert into game(id,data) values(?,?)", otherGameId, serializeSteps(6));
        jdbc.update("insert into record(player1,name1,player2,name2,start,end,win,steps,data,type) "
                        + "values(?,?,?,?,?,?,?,?,?,?)",
                FAKE_OPPONENT, "对手乙", FAKE_OPPONENT + 1, "对手丙", "2026-02-02 10:00:00", "2026-02-02 10:10:00",
                null, 6, otherGameId, "normal");
        postFail("/getGameData", "{\"id\":\"" + otherGameId + "\",\"token\":\"" + token + "\"}",
                403, "无权查看该对局");
    }

    @Test
    @Order(8)
    void nicknameMustBeUnique() {
        String otherLogin = "itunick" + String.format("%04d", new Random().nextInt(10000));
        //同一昵称不能被其它账号再次使用
        postFail("/registerPost",
                "{\"id\":\"" + otherLogin + "\",\"name\":\"" + NAME + "\",\"password\":\"" + PASSWORD + "\"}",
                409, "昵称已被使用");
        assertEquals(0, jdbc.queryForObject("select count(*) from user where login = ?", Integer.class, otherLogin),
                "重复昵称的账号不应写入数据库");

        //昵称唯一由数据库唯一索引兜底
        Integer indexCount = jdbc.queryForObject(
                "select count(*) from information_schema.statistics where table_schema = database() "
                        + "and table_name = 'user' and index_name = 'uk_user_name'", Integer.class);
        assertTrue(indexCount != null && indexCount > 0, "user.name 应存在唯一索引 uk_user_name");

        //换一个未被使用的昵称可以正常注册（随后清理）
String spareLogin = "ituspare" + String.format("%04d", new Random().nextInt(10000));
        //昵称上限为 10，备用账号名有 12 位，不能直接复用为昵称
        String spareName = "备" + String.format("%09d", new Random().nextInt(1000000000));
        postJson("/registerPost", "{\"id\":\"" + spareLogin + "\",\"name\":\"" + spareName
                + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(1, jdbc.queryForObject("select count(*) from user where login = ?", Integer.class, spareLogin),
                "唯一昵称应能正常注册");
        jdbc.update("delete from user where login = ?", spareLogin);
    }

    @AfterAll
    void cleanUp() {
        for (String gameId : gameIds) {
            jdbc.update("delete from record where data = ?", gameId);
            jdbc.update("delete from game where id = ?", gameId);
        }
        if (userId != null) {
            jdbc.update("delete from record where player1 = ? or player2 = ?", userId, userId);
        }
        //只删除本类创建的测试账号与用例数据
        jdbc.update("delete from user where login = ?", LOGIN);
        jdbc.update("delete from user where login = ?", LEGACY_LOGIN);
        assertEquals(0, jdbc.queryForObject("select count(*) from user where login = ?", Integer.class, LOGIN),
                "测试账号应已清理");
        assertEquals(0, jdbc.queryForObject("select count(*) from user where login = ?", Integer.class, LEGACY_LOGIN),
                "历史账号用例数据应已清理");
    }

    private static String repeat(String value, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(value);
        }
        return sb.toString();
    }

    private static byte[] serializeSteps(int count) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            steps.add(new Step(i % 2 + 1, i, i, i % 2 == 0 ? "black" : "white"));
        }
        return serializeSteps(steps);
    }

    private static byte[] serializeSteps(List<Step> steps) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bos);
            out.writeObject(new Steps(steps));
            out.flush();
            out.close();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("序列化对局步数失败", e);
        }
    }
}