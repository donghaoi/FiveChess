package com.whc.fivechess;

import com.whc.fivechess.component.AvatarService;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /avatar/{id} 的条件请求（If-None-Match → 304）测试。
 *
 * 背景：该接口原先无论浏览器是否已缓存，都会把 content 大字段整行读出来再决定返回 200 还是 304。
 * 改造后带 ETag 的复验只查 version 单列，命中即 304。
 *
 * 测试数据直接写库构造，不走注册/登录：本类只验证读接口，登录态与限流都是无关变量，
 * 这样既不会被同批次其它测试类的注册限流误伤，也不会因为令牌刷新互相干扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvatarConditionalTests {

    /** 1x1 的合法 JPEG */
    private static final byte[] JPEG = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcp"
                    + "LDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAA"
                    + "AAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==");

    private static final String SUFFIX = String.format("%06d", new Random().nextInt(1000000));
    private static final String LOGIN = "avc" + SUFFIX;
    /** 初始版本号故意用一个固定值，断言里直接比对字面量，避免「自己算出来自己验」 */
    private static final long VERSION = 1732000000000L;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AvatarService avatarService;

    private Integer userId;
    /** 库里有头像记录的用户id（用于验证带 ETag 也不会误判成 304） */
    private Integer emptyUserId;

    @BeforeAll
    void setUp() {
        jdbc.update("INSERT INTO user(login,name,password,time) VALUES(?,?,?,?)",
                LOGIN, "条件请求" + SUFFIX.substring(4), "temp-hash", "2026-01-01 00:00:00");
        userId = jdbc.queryForObject("SELECT id FROM user WHERE login = ?", Integer.class, LOGIN);
        jdbc.update("INSERT INTO user_avatar(user_id,content,content_type,version,updated_at) VALUES(?,?,?,?,?)",
                userId, JPEG, "image/jpeg", VERSION, "2026-01-01 00:00:00");

        jdbc.update("INSERT INTO user(login,name,password,time) VALUES(?,?,?,?)",
                LOGIN + "e", "无头像" + SUFFIX.substring(4), "temp-hash", "2026-01-01 00:00:00");
        emptyUserId = jdbc.queryForObject("SELECT id FROM user WHERE login = ?", Integer.class, LOGIN + "e");
    }

    @AfterAll
    void tearDown() {
        try {
            if (userId != null) {
                jdbc.update("DELETE FROM user_avatar WHERE user_id = ?", userId);
            }
            jdbc.update("DELETE FROM user WHERE login = ?", LOGIN);
            jdbc.update("DELETE FROM user WHERE login = ?", LOGIN + "e");
        } catch (Exception ignored) {
            //清理失败不影响测试结论
        }
    }

    private String etag() {
        return "\"" + VERSION + "\"";
    }

    /** 带 If-None-Match 请求头像；ifNoneMatch 传 null 表示首次访问 */
    private ResponseEntity<byte[]> get(String path, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (ifNoneMatch != null) {
            headers.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private String avatarPath() {
        return AvatarService.AVATAR_API_PREFIX + userId;
    }

    @Test
    @Order(1)
    void 首次访问返回200并下发ETag与缓存头() {
        ResponseEntity<byte[]> response = get(avatarPath(), null);
        assertEquals(200, response.getStatusCodeValue(), "首次访问应返回 200");
        assertArrayEquals(JPEG, response.getBody(), "应返回完整图片字节");
        assertEquals(etag(), response.getHeaders().getETag(), "ETag 应为版本号的强标签");
        String cacheControl = response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
        assertNotNull(cacheControl, "应带 Cache-Control");
        assertTrue(cacheControl.contains("max-age="), "缓存头应包含 max-age：" + cacheControl);
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    @Test
    @Order(2)
    void 携带最新ETag复验返回304且不带响应体() {
        ResponseEntity<byte[]> response = get(avatarPath(), etag());
        assertEquals(304, response.getStatusCodeValue(), "版本号未变应返回 304");
        assertTrue(response.getBody() == null || response.getBody().length == 0,
                "304 不应返回图片字节");
        //304 也必须回带 ETag 与缓存头，否则浏览器会认为缓存已失效、立刻再请求一次
        assertEquals(etag(), response.getHeaders().getETag(), "304 应回带 ETag");
        String cacheControl = response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
        assertNotNull(cacheControl, "304 应回带 Cache-Control");
        assertTrue(cacheControl.contains("max-age="), "304 的缓存头应包含 max-age：" + cacheControl);
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"),
                "304 也应带 nosniff");
    }

    @Test
    @Order(3)
    void 弱标签W前缀也能命中304() {
        ResponseEntity<byte[]> response = get(avatarPath(), "W/" + etag());
        assertEquals(304, response.getStatusCodeValue(),
                "按 RFC 9110 应使用弱比较，W/\"xxx\" 与 \"xxx\" 视为同一版本");
    }

    @Test
    @Order(4)
    void 多标签列表命中其一即返回304() {
        ResponseEntity<byte[]> response = get(avatarPath(), "\"1700000000000\", " + etag());
        assertEquals(304, response.getStatusCodeValue(),
                "If-None-Match 允许携带多个缓存副本的 ETag，任一匹配即未修改");
    }

    @Test
    @Order(5)
    void 星号通配符命中304() {
        ResponseEntity<byte[]> response = get(avatarPath(), "*");
        assertEquals(304, response.getStatusCodeValue(), "* 表示任意当前表示都算命中");
    }

    @Test
    @Order(6)
    void ETag不匹配时仍返回完整图片() {
        ResponseEntity<byte[]> response = get(avatarPath(), "\"1700000000000\"");
        assertEquals(200, response.getStatusCodeValue(), "版本号不同应返回 200，让浏览器换图");
        assertArrayEquals(JPEG, response.getBody(), "应返回完整图片字节");
        assertEquals(etag(), response.getHeaders().getETag(), "应下发新的 ETag");
    }

    @Test
    @Order(7)
    void 没有头像记录时带ETag仍返回404() {
        ResponseEntity<byte[]> response = get(AvatarService.AVATAR_API_PREFIX + emptyUserId, etag());
        assertEquals(404, response.getStatusCodeValue(),
                "无上传记录时即使带了 ETag 也应 404，不能被短路成 304");
    }

    @Test
    @Order(8)
    void 非法用户id带ETag仍返回404而不是500() {
        assertEquals(404, get("/avatar/abc", etag()).getStatusCodeValue(),
                "非数字 id 应 404");
        assertEquals(404, get("/avatar/999999999", etag()).getStatusCodeValue(),
                "不存在的 id 应 404");
        assertEquals(404, get("/avatar/-1", etag()).getStatusCodeValue(),
                "负数 id 应 404");
    }

    @Test
    @Order(9)
    void 换头像后旧ETag立即失效() {
        long newVersion = VERSION + 1000L;
        jdbc.update("UPDATE user_avatar SET version = ? WHERE user_id = ?", newVersion, userId);
        try {
            ResponseEntity<byte[]> stale = get(avatarPath(), etag());
            assertEquals(200, stale.getStatusCodeValue(),
                    "换头像（版本号变化）后，浏览器持有的旧 ETag 必须失效并拿到新图");
            assertArrayEquals(JPEG, stale.getBody(), "应返回当前图片字节");
            assertEquals("\"" + newVersion + "\"", stale.getHeaders().getETag(),
                    "应下发新版本号的 ETag");

            assertEquals(304, get(avatarPath(), "\"" + newVersion + "\"").getStatusCodeValue(),
                    "改用新 ETag 复验应命中 304");
        } finally {
            jdbc.update("UPDATE user_avatar SET version = ? WHERE user_id = ?", VERSION, userId);
        }
    }

    @Test
    @Order(10)
    void 版本号查询只读单列且与库内一致() {
        assertEquals(Long.valueOf(VERSION), avatarService.versionOf(String.valueOf(userId)),
                "versionOf 应返回库内版本号");
        assertNull(avatarService.versionOf(String.valueOf(emptyUserId)),
                "没有上传记录的用户应返回 null，调用方据此退回完整查询");
        assertNull(avatarService.versionOf("abc"), "非法 id 应返回 null 而不是抛异常");
        assertNull(avatarService.versionOf(null), "null id 应返回 null 而不是抛异常");
    }
}
