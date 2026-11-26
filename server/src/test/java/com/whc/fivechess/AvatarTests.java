package com.whc.fivechess;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.component.AvatarService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import com.whc.fivechess.support.TestWsClient;
import org.springframework.boot.web.server.LocalServerPort;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 头像功能测试。
 *
 * 存储方式已从「写服务器文件」改为「图片存数据库 user_avatar 表 + GET /avatar/{id} 输出」，
 * 因此断言重点为：上传后库里只有一条记录、字节与上传一致、访问地址带版本号、
 * 读取接口的 Content-Type / 缓存头 / 404 行为正确，且整个过程不再产生任何磁盘文件。
 *
 * 使用 application.yml 指向的真实数据库，测试账号使用 avt 前缀，结束后清理账号与头像记录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AvatarTests {

    /** 1x1 的合法 JPEG */
    private static final String JPEG_BASE64 =
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcp"
                    + "LDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAA"
                    + "AAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==";
    /** 1x1 的合法 PNG */
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private static final String SUFFIX = String.format("%06d", new Random().nextInt(1000000));
    private static final String LOGIN = "avt" + SUFFIX;
    private static final String NAME = "头像测试" + SUFFIX.substring(4);
    private static final String PASSWORD = "avt123456";
    /** 房间用例里创建的对手账号 */
    private static final String LOGIN_B = "avtb" + SUFFIX;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AvatarService avatarService;
    @Value("${fivechess.admin.login:admin}")
    private String adminLogin;
    /** 前端静态资源基路径：头像地址会带上这个前缀，断言期望值必须按它计算 */
    @Value("${fivechess.avatar.static-base:}")
    private String avatarStaticBase;
    @LocalServerPort
    private int port;

    /**
     * 默认头像的期望地址：AvatarService.DEFAULT_AVATAR 只是「页面根相对路径」，
     * 实际下发时会拼上 static-base（本项目部署在 /box/Chess 子路径下）。
     * 之前用例直接拿常量当期望值，改动基路径后必然失败——这里按配置拼出真实地址。
     */
    private String expectedDefaultAvatar() {
        String base = avatarStaticBase == null ? "" : avatarStaticBase.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + AvatarService.DEFAULT_AVATAR;
    }

    private Integer userId;
    private String token;

    /** 管理员头像记录的原始状态：测试结束后原样还原 */
    private Integer adminId;
    private boolean createdAdmin;
    private boolean adminRowExisted;
    private byte[] adminContentBackup;
    private String adminContentTypeBackup;
    private Long adminVersionBackup;
    private String adminAvatarColumnBackup;
    private boolean adminColumnExisted;

    /** 组装请求体：hutool 4.0.12 的 JSONObject.put 返回值不可链式调用，这里统一用 Map 转 JSON */
    private String json(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return JSONUtil.toJsonStr(map);
    }

    private JSONObject post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCodeValue(), "HTTP 状态码应为 200：" + path);
        assertNotNull(response.getBody(), "响应体不应为空：" + path);
        return JSONUtil.parseObj(response.getBody());
    }

    private JSONObject login() {
        JSONObject result = post("/loginPost", json("login", LOGIN, "password", PASSWORD));
        //每次登录都会下发新令牌，旧令牌立即失效：这里同步记下最新的一次，供后续用例使用
        if (result.getInt("code") == 200 && result.getJSONObject("content") != null) {
            JSONObject content = result.getJSONObject("content");
            if (content.get("id") != null) {
                userId = content.getInt("id");
            }
            token = content.getStr("token");
        }
        return result;
    }

    private String dataUrl(String base64) {
        return "data:image/jpeg;base64," + base64;
    }

    private JSONObject upload(String image) {
        return post("/avatarUploadPost", json(
                "id", String.valueOf(userId), "token", token, "image", image));
    }

    /** 上传头像地址应形如 /avatar/{id}?v=毫秒 */
    private void assertUploadedUrl(String avatar, int id) {
        assertNotNull(avatar, "应返回头像地址");
        assertTrue(avatar.startsWith(AvatarService.AVATAR_API_PREFIX + id),
                "上传后应指向后端读取接口 /avatar/" + id + "：" + avatar);
        assertTrue(avatar.contains("?v="), "地址应带版本号，避免浏览器显示旧图：" + avatar);
    }

    /** 读取图片接口，返回原始响应 */
    private ResponseEntity<byte[]> getImage(String path) {
        return rest.getForEntity(path, byte[].class);
    }

    /** 头像不应再落到任何本地目录：检查常见工作目录下的 user 目录 */
    private boolean anyUploadFileOnDisk(String id) {
        String[] roots = {"web/static/image/user", "../web/static/image/user",
                "static/image/user", "../static/image/user"};
        for (String root : roots) {
            Path dir = Paths.get(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, id + ".*")) {
                if (stream.iterator().hasNext()) {
                    return true;
                }
            } catch (IOException ignored) {
                //目录不可读时跳过
            }
        }
        return false;
    }

    private long avatarRows(int id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_avatar WHERE user_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    @BeforeAll
    void setUp() {
        JSONObject reg = post("/registerPost", json(
                "id", LOGIN, "name", NAME, "password", PASSWORD));
        assertEquals(200, reg.getInt("code"), "注册应成功：" + reg);
        JSONObject login = login();
        assertEquals(200, login.getInt("code"), "登录应成功：" + login);
        JSONObject content = login.getJSONObject("content");
        userId = content.getInt("id");
        token = content.getStr("token");
    }

    @AfterAll
    void tearDown() {
        try {
            if (userId != null) {
                jdbc.update("DELETE FROM user_avatar WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM user WHERE login = ?", LOGIN);
            }
            jdbc.update("DELETE FROM user WHERE login = ?", LOGIN_B);
            //还原管理员头像记录与 user.avatar
            if (adminId != null) {
                jdbc.update("DELETE FROM user_avatar WHERE user_id = ?", adminId);
                if (adminRowExisted) {
                    jdbc.update("INSERT INTO user_avatar(user_id,content,content_type,version,updated_at) VALUES(?,?,?,?,?)",
                            adminId, adminContentBackup, adminContentTypeBackup, adminVersionBackup,
                            "2026-01-01 00:00:00");
                }
                if (adminColumnExisted) {
                    jdbc.update("UPDATE user SET avatar = ? WHERE id = ?", adminAvatarColumnBackup, adminId);
                } else {
                    jdbc.update("UPDATE user SET avatar = NULL WHERE id = ?", adminId);
                }
            }
            if (createdAdmin) {
                jdbc.update("DELETE FROM user WHERE login = ?", adminLogin);
            }
        } catch (Exception ignored) {
            //还原失败不影响测试结论
        }
    }

    @Test
    @Order(1)
    void 普通用户登录随机分配内置头像() {
        JSONObject content = login().getJSONObject("content");
        String avatar = content.getStr("avatar");
        assertNotNull(avatar, "登录响应应包含头像：" + content);
        assertTrue(avatarService.builtinCount() > 0, "内置头像清单不应为空");
        assertTrue(avatarService.isBuiltinAvatar(avatar),
                "未上传过头像的普通用户应分配到清单内的内置头像：" + avatar);
    }

    @Test
    @Order(2)
    void 每次登录都重新分配且会话内保持一致() {
        String first = login().getJSONObject("content").getStr("avatar");
        String second = login().getJSONObject("content").getStr("avatar");
        assertTrue(avatarService.isBuiltinAvatar(first) && avatarService.isBuiltinAvatar(second),
                "两次登录都应分配内置头像：" + first + " / " + second);
        //本次会话内查询接口应与最近一次登录分配的结果一致
        JSONObject current = post("/avatarPost", json(
                "id", String.valueOf(userId), "token", token));
        assertEquals(200, current.getInt("code"), "查询头像应成功：" + current);
        String queried = current.getJSONObject("content").getStr("avatar");
        assertTrue(avatarService.isBuiltinAvatar(queried), "查询接口应返回内置头像：" + queried);
    }

    @Test
    @Order(3)
    void 上传头像写入数据库并可从接口读回() {
        byte[] expected = Base64.getDecoder().decode(JPEG_BASE64);
        JSONObject result = upload(dataUrl(JPEG_BASE64));
        assertEquals(200, result.getInt("code"), "上传头像应成功：" + result);
        String avatar = result.getJSONObject("content").getStr("avatar");
        assertUploadedUrl(avatar, userId);

        //库里只保留一条记录，且字节与上传内容完全一致
        assertEquals(1L, avatarRows(userId), "user_avatar 应有且仅有一条本人记录");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT content, content_type, version FROM user_avatar WHERE user_id = ?", userId);
        assertArrayEquals(expected, (byte[]) row.get("content"), "入库字节应与上传内容一致");
        assertEquals("image/jpeg", row.get("content_type"), "应按文件头识别为 jpeg");
        assertEquals("image/jpeg", jdbc.queryForObject(
                "SELECT content_type FROM user_avatar WHERE user_id = ?", String.class, userId));

        //user.avatar 记录访问路径，不含版本号
        assertEquals(AvatarService.AVATAR_API_PREFIX + userId,
                jdbc.queryForObject("SELECT avatar FROM user WHERE id = ?", String.class, userId),
                "数据库应记录不带版本号的头像路径");

        //读取接口应原样返回图片
        ResponseEntity<byte[]> image = getImage(AvatarService.AVATAR_API_PREFIX + userId);
        assertEquals(200, image.getStatusCodeValue(), "读取头像应返回 200");
        assertArrayEquals(expected, image.getBody(), "接口返回的字节应与上传一致");
        assertEquals(MediaType.IMAGE_JPEG, image.getHeaders().getContentType(),
                "响应 Content-Type 应为 image/jpeg");
        assertNotNull(image.getHeaders().getFirst("Cache-Control"), "应带缓存头，避免每次进页面都重复请求");
        assertTrue(image.getHeaders().getFirst("Cache-Control").contains("max-age="),
                "缓存头应包含 max-age：" + image.getHeaders().getFirst("Cache-Control"));
        assertNotNull(image.getHeaders().getETag(), "应带 ETag（版本号）");
        assertEquals("nosniff", image.getHeaders().getFirst("X-Content-Type-Options"));

        //全程不应产生磁盘文件
        assertFalse(anyUploadFileOnDisk(String.valueOf(userId)),
                "头像已改为入库，不应再写入任何本地文件");
    }

    @Test
    @Order(4)
    void 再次上传替换同一条记录并换新版本号() {
        Long versionBefore = jdbc.queryForObject(
                "SELECT version FROM user_avatar WHERE user_id = ?", Long.class, userId);
        byte[] expected = Base64.getDecoder().decode(PNG_BASE64);

        JSONObject result = upload("data:image/png;base64," + PNG_BASE64);
        assertEquals(200, result.getInt("code"), "换成 PNG 应成功：" + result);
        String avatar = result.getJSONObject("content").getStr("avatar");
        assertUploadedUrl(avatar, userId);

        assertEquals(1L, avatarRows(userId), "再次上传应覆盖，而不是新增一条记录");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT content, content_type, version FROM user_avatar WHERE user_id = ?", userId);
        assertArrayEquals(expected, (byte[]) row.get("content"), "库内图片应替换为新的 PNG");
        assertEquals("image/png", row.get("content_type"), "类型应更新为 png");
        Long versionAfter = (Long) row.get("version");
        assertTrue(versionAfter > versionBefore,
                "版本号应递增，前端才能拿到新图：" + versionBefore + " -> " + versionAfter);

        ResponseEntity<byte[]> image = getImage(AvatarService.AVATAR_API_PREFIX + userId);
        assertEquals(MediaType.IMAGE_PNG, image.getHeaders().getContentType(),
                "Content-Type 应随图片类型变化");
        assertArrayEquals(expected, image.getBody(), "接口应返回最新图片");
    }

    @Test
    @Order(5)
    void 上传过的用户再次登录使用本人头像() {
        String avatar = login().getJSONObject("content").getStr("avatar");
        assertTrue(avatar.startsWith(AvatarService.AVATAR_API_PREFIX + userId),
                "上传过头像的用户登录后应使用本人头像，而不是重新随机：" + avatar);
        assertTrue(avatar.contains("?v="), "登录下发的地址应带版本号：" + avatar);
    }

    @Test
    @Order(6)
    void 非法请求与不存在的头像被正确处理() {
        //令牌错误
        JSONObject badToken = post("/avatarUploadPost", json(
                "id", String.valueOf(userId), "token", "wrong-token", "image", dataUrl(JPEG_BASE64)));
        assertEquals(401, badToken.getInt("code"), "令牌无效应返回 401：" + badToken);

        //缺少图片
        JSONObject empty = upload("");
        assertEquals(400, empty.getInt("code"), "未选择图片应返回 400：" + empty);

        //不是图片（纯文本 base64）
        String notImage = "data:image/jpeg;base64," + Base64.getEncoder()
                .encodeToString("this is not an image".getBytes());
        JSONObject fake = upload(notImage);
        assertEquals(400, fake.getInt("code"), "非图片数据应被拒绝：" + fake);

        //超过大小上限（4MB 的伪造数据）
        byte[] big = new byte[4 * 1024 * 1024];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        JSONObject huge = upload(dataUrl(Base64.getEncoder().encodeToString(big)));
        assertEquals(400, huge.getInt("code"), "超过 3MB 应被拒绝：" + huge);

        //查询接口同样校验令牌
        JSONObject query = post("/avatarPost", json(
                "id", String.valueOf(userId), "token", "wrong-token"));
        assertEquals(401, query.getInt("code"), "查询头像令牌无效应返回 401：" + query);

        //不存在的用户 / 非法 id：读取接口返回 404，前端据此回退默认头像
        assertEquals(404, getImage("/avatar/999999999").getStatusCodeValue(),
                "未上传头像的用户应返回 404");
        assertEquals(404, getImage("/avatar/abc").getStatusCodeValue(),
                "非法 id 应返回 404 而不是 500");
    }

    @Test
    @Order(7)
    void 管理员未上传时用默认图上传后走读取接口() throws Exception {
        List<Integer> adminIds = jdbc.queryForList(
                "SELECT id FROM user WHERE login = ?", Integer.class, adminLogin);
        //单独运行本测试类时库里可能还没有管理员账号：临时建一个，测完删除
        if (adminIds.isEmpty()) {
            jdbc.update("INSERT INTO user(login,name,password,time) VALUES(?,?,?,?)",
                    adminLogin, "临时管理" + SUFFIX.substring(4), "temp-admin-hash", "2026-01-01 00:00:00");
            createdAdmin = true;
            adminIds = jdbc.queryForList(
                    "SELECT id FROM user WHERE login = ?", Integer.class, adminLogin);
        }
        assumeTrue(!adminIds.isEmpty(), "无法准备管理员账号，跳过该用例");
        adminId = adminIds.get(0);

        //备份管理员当前的头像记录与 user.avatar
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT content, content_type, version FROM user_avatar WHERE user_id = ?", adminId);
        adminRowExisted = !rows.isEmpty();
        if (adminRowExisted) {
            adminContentBackup = (byte[]) rows.get(0).get("content");
            adminContentTypeBackup = (String) rows.get(0).get("content_type");
            adminVersionBackup = ((Number) rows.get(0).get("version")).longValue();
        }
        List<String> oldAvatar = jdbc.queryForList(
                "SELECT avatar FROM user WHERE id = ?", String.class, adminId);
        adminAvatarColumnBackup = oldAvatar.isEmpty() ? null : oldAvatar.get(0);
        adminColumnExisted = adminAvatarColumnBackup != null;

        //未上传过时，管理员头像固定为前端静态的 avatar.webp
        String beforeUpload = avatarService.avatarForLogin(
                jdbc.queryForObject("SELECT * FROM user WHERE id = ?",
                        (rs, i) -> {
                            com.whc.fivechess.domain.User u = new com.whc.fivechess.domain.User();
                            u.setId(rs.getInt("id"));
                            u.setLogin(rs.getString("login"));
                            return u;
                        }, adminId));
        if (!adminRowExisted) {
            assertEquals(expectedDefaultAvatar(), beforeUpload,
                    "管理员未上传头像时应使用默认图：" + beforeUpload);
        }

        try {
            String avatar = avatarService.saveAvatar(String.valueOf(adminId), dataUrl(JPEG_BASE64));
            assertUploadedUrl(avatar, adminId);
            assertEquals(1L, avatarRows(adminId), "管理员上传后应有一条记录");
            assertEquals(AvatarService.AVATAR_API_PREFIX + adminId,
                    jdbc.queryForObject("SELECT avatar FROM user WHERE id = ?", String.class, adminId),
                    "管理员数据库记录应指向读取接口");
            //不再覆盖前端静态文件：上传结果只存库
            assertFalse(anyUploadFileOnDisk(String.valueOf(adminId)),
                    "管理员上传同样不应写本地文件");
            assertArrayEquals(Base64.getDecoder().decode(JPEG_BASE64),
                    getImage(AvatarService.AVATAR_API_PREFIX + adminId).getBody(),
                    "管理员头像应能通过读取接口取回");
        } finally {
            //立即还原，避免影响后续用例与库内数据
            jdbc.update("DELETE FROM user_avatar WHERE user_id = ?", adminId);
            if (adminRowExisted) {
                jdbc.update("INSERT INTO user_avatar(user_id,content,content_type,version,updated_at) VALUES(?,?,?,?,?)",
                        adminId, adminContentBackup, adminContentTypeBackup, adminVersionBackup,
                        "2026-01-01 00:00:00");
            }
            if (adminColumnExisted) {
                jdbc.update("UPDATE user SET avatar = ? WHERE id = ?", adminAvatarColumnBackup, adminId);
            } else {
                jdbc.update("UPDATE user SET avatar = NULL WHERE id = ?", adminId);
            }
            adminId = null;
            adminRowExisted = false;
            adminContentBackup = null;
            adminContentTypeBackup = null;
            adminVersionBackup = null;
            adminAvatarColumnBackup = null;
            adminColumnExisted = false;
        }
    }

    @Test
    @Order(8)
    void 房间卡片按人展示各自头像() throws Exception {
        String avatarA = post("/avatarPost", json(
                "id", String.valueOf(userId), "token", token))
                .getJSONObject("content").getStr("avatar");
        assertNotNull(avatarA, "应能取到本人头像");

        JSONObject regB = post("/registerPost", json(
                "id", LOGIN_B, "name", "头像客" + SUFFIX.substring(4), "password", PASSWORD));
        assertEquals(200, regB.getInt("code"), "注册对手账号应成功：" + regB);
        JSONObject loginB = post("/loginPost", json("login", LOGIN_B, "password", PASSWORD));
        JSONObject contentB = loginB.getJSONObject("content");
        String idB = String.valueOf(contentB.get("id"));
        String tokenB = contentB.getStr("token");
        String avatarB = contentB.getStr("avatar");
        assertNotNull(avatarB, "对手登录应带头像");

        TestWsClient clientA = TestWsClient.connectAuthed(port, String.valueOf(userId), token);
        TestWsClient clientB = TestWsClient.connectAuthed(port, idB, tokenB);
        try {
            assertNotNull(clientA.await("auth-success", 5000), "房主应鉴权成功");
            assertNotNull(clientB.await("auth-success", 5000), "对手应鉴权成功");

            clientA.send("{\"type\":\"create-room\"}");
            String created = clientA.await("create-room-success", 5000);
            assertNotNull(created, "应能创建房间");
            JSONObject view = JSONUtil.parseObj(created).getJSONObject("content");
            assertEquals(avatarA, view.getStr("playerAvatar1"),
                    "房主头像应下发给大厅卡片：" + view);
            assertEquals(expectedDefaultAvatar(), view.getStr("playerAvatar2"),
                    "空座位应显示默认头像：" + view);

            String roomId = view.getStr("id");
            clientB.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\"}");
            String joined = clientB.await("join-room-success", 5000);
            assertNotNull(joined, "对手应能加入房间");
            JSONObject joinedView = JSONUtil.parseObj(joined).getJSONObject("content");
            assertEquals(avatarB, joinedView.getStr("playerAvatar2"),
                    "加入者的头像应下发给大厅卡片：" + joinedView);
            assertEquals(avatarA, joinedView.getStr("playerAvatar1"),
                    "房主头像不应被加入者覆盖：" + joinedView);
        } finally {
            clientA.close();
            clientB.close();
            jdbc.update("DELETE FROM user_avatar WHERE user_id = ?", Integer.valueOf(idB));
            jdbc.update("DELETE FROM user WHERE login = ?", LOGIN_B);
        }
    }

    @Test
    @Order(9)
    void 非数字id一律404() {
        //数据库对字符串做隐式类型转换，"1abc" 会被当成 1，于是任何以数字开头的怪路径
        //都能读到该用户头像。接口应先做纯数字校验。
        String[] paths = {
                "/avatar/" + userId + "abc",
                "/avatar/%20" + userId,
                "/avatar/-1",
                "/avatar/" + userId + ".0",
                "/avatar/0x" + userId,
        };
        for (String path : paths) {
            ResponseEntity<byte[]> resp = getImage(path);
            assertEquals(404, resp.getStatusCodeValue(), "非数字 id 应返回 404：" + path);
        }
        //纯数字 id 仍然正常
        assertEquals(200, getImage("/avatar/" + userId).getStatusCodeValue(), "合法 id 不受影响");

        //带分号的路径由 Spring 按矩阵参数截断（分号后内容被丢弃），等价于合法 id，
        //这里显式固化该行为：拿到的仍是本人头像，而不是拼接出来的怪值去查库
        ResponseEntity<byte[]> semi = getImage("/avatar/" + userId + ";DROP%20TABLE%20user");
        assertEquals(200, semi.getStatusCodeValue(), "分号后的内容应被框架截断");
        assertArrayEquals(getImage("/avatar/" + userId).getBody(), semi.getBody(),
                "分号路径应返回与合法路径完全相同的图片");
    }
}
