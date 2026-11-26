package com.whc.fivechess;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.component.ModerationService;
import com.whc.fivechess.security.AbuseGuard;
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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码审查发现问题的回归用例（每个用例对应一条已修复缺陷）。
 *
 * 1. 局部更新不得把禁言/封号状态写回 0；
 * 2. 未鉴权连接不能凭别人的 id 顶掉在线用户、也不能收到发给对方的私信；
 * 3. 退出登录后已建立的 WebSocket 必须立即失效；
 * 4. 没有对应请求时，单方发「同意」不能悔棋/判和/重开；
 * 5. 超大请求体在进入业务前就被拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewRegressionTests {

    private static final long TIMEOUT = 5000L;
    private static final String PASSWORD = "rv123456";
    private static final Random RANDOM = new Random();
    /** 1x1 的合法 JPEG，用于头像上传校验 */
    private static final String TINY_JPEG = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/E"
            + "ABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ModerationService moderationService;

    private final Map<String, String> tokens = new HashMap<>();

    @BeforeAll
    void setUp() {
        //本类要连发多次注册/登录，先关闭限流，避免被测逻辑被 429 掩盖。
        //必须复用与其它用例相同的 Spring 上下文：SocketServer 的依赖是静态注入的，
        //换一套上下文会让 WS 鉴权拿到另一套 TokenStore，导致后续用例莫名鉴权失败。
        AbuseGuard.setEnforced(false);
    }

    @AfterAll
    void tearDown() {
        //保持关闭：同一 JVM 内后续用例会继续注册/登录，若在这里恢复限流，
        //累计计数会超过 10 次/分钟，导致后面的用例被 429 误伤（用例本身不校验 429）
        AbuseGuard.setEnforced(false);
    }

    /** 审查缺陷 1：上传头像只应改 avatar，不能把 muted/banned 一起写回 0 */
    @Test
    @Order(1)
    void avatarUploadKeepsModerationFlags() {
        String id = registerAndLogin("rvav");
        jdbc.update("update user set muted = 1, banned = 0 where id = ?", Integer.valueOf(id));

        JSONObject result = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + tokens.get(id)
                        + "\",\"image\":\"data:image/jpeg;base64," + TINY_JPEG + "\"}");
        assertEquals(200, result.getInt("code"), "头像上传应成功：" + result);

        Integer muted = jdbc.queryForObject("select muted from user where id = ?", Integer.class, Integer.valueOf(id));
        assertEquals(1, muted.intValue(), "上传头像不能把禁言状态清掉");
    }

    /** 审查缺陷 1（续）：解除禁言只应改 muted，不能顺带把封号标记清掉 */
    @Test
    @Order(2)
    void muteUpdateKeepsBanFlag() {
        String id = registerAndLogin("rvbn");
        jdbc.update("update user set banned = 1 where id = ?", Integer.valueOf(id));

        assertTrue(moderationService.updateMuted(Integer.valueOf(id), true), "更新禁言状态应成功");

        Integer banned = jdbc.queryForObject("select banned from user where id = ?", Integer.class, Integer.valueOf(id));
        String registerIp = jdbc.queryForObject("select register_ip from user where id = ?", String.class, Integer.valueOf(id));
        assertEquals(1, banned.intValue(), "改禁言状态不能顺带解封");
        assertEquals("127.0.0.1", registerIp, "改禁言状态不能清掉账号的来源 IP 记录");

        //还原，避免影响其它用例的 IP 封禁判断
        jdbc.update("update user set banned = 0, muted = 0 where id = ?", Integer.valueOf(id));
    }

    /** 审查缺陷 2：不带令牌、直接用别人的 id 建连，不能把对方顶下线 */
    @Test
    @Order(3)
    void unauthenticatedConnectionCannotHijack() throws Exception {
        String id = registerAndLogin("rvhj");
        TestWsClient victim = TestWsClient.connectAuthed(port, id, tokens.get(id));
        assertNotNull(victim.await("auth-success", TIMEOUT), "受害者应正常鉴权");

        //攻击者只知道 id，没有令牌
        TestWsClient attacker = TestWsClient.connect(port, id);
        Thread.sleep(800);

        assertFalse(victim.isClosed(), "未鉴权连接不能把已鉴权的连接顶下线");
        assertEquals(0, victim.count("kicked"), "受害者不应收到顶号通知");
        assertEquals(0, attacker.count("auth-success"), "攻击连接不应被当作已鉴权");
        assertEquals(0, attacker.count("online-user-list"), "未鉴权连接不应收到在线列表");
        assertEquals(0, attacker.count("public-chat-message"), "未鉴权连接不应收到公共聊天");

        attacker.close();
        //受害者仍然可用：发一条消息应能正常处理
        victim.send("{\"type\":\"report-nick\",\"userId\":\"" + id + "\",\"nickName\":\"正常用户\"}");
        Thread.sleep(300);
        assertFalse(victim.isClosed(), "顶号尝试后原连接应保持可用");
        victim.close();
    }

    /** 审查缺陷 3：退出登录后，已建立的 WebSocket 必须立刻失效 */
    @Test
    @Order(4)
    void socketClosedAfterLogout() throws Exception {
        String id = registerAndLogin("rvlo");
        TestWsClient client = TestWsClient.connectAuthed(port, id, tokens.get(id));
        assertNotNull(client.await("auth-success", TIMEOUT), "应正常鉴权");

        post("/logoutPost", "{\"id\":\"" + id + "\",\"token\":\"" + tokens.get(id) + "\"}");

        assertTrue(client.awaitClosed(TIMEOUT), "退出登录后长连接应被服务端关闭");
        assertEquals(0, client.count("public-chat-message"), "退出后不应再收到广播");
    }

    /** 审查缺陷 4：没有对方请求时，单方「同意和棋」不能生效 */
    @Test
    @Order(5)
    void agreeWithoutRequestRejected() throws Exception {
        String idA = registerAndLogin("rvpa");
        String idB = registerAndLogin("rvpb");
        TestWsClient a = TestWsClient.connectAuthed(port, idA, tokens.get(idA));
        TestWsClient b = TestWsClient.connectAuthed(port, idB, tokens.get(idB));
        assertNotNull(a.await("auth-success", TIMEOUT), "A 应鉴权");
        assertNotNull(b.await("auth-success", TIMEOUT), "B 应鉴权");

        int created = a.count("create-room-success");
        a.send("{\"type\":\"create-room\",\"user\":" + userJson(idA) + "}");
        assertTrue(a.awaitCount("create-room-success", created + 1, TIMEOUT), "建房应成功");
        String roomId = JSONUtil.parseObj(lastOf(a, "create-room-success"))
                .getJSONObject("content").getStr("id");

        int joined = b.count("join-room-success");
        b.send("{\"type\":\"join-room\",\"roomId\":\"" + roomId + "\",\"user\":" + userJson(idB) + "}");
        assertTrue(b.awaitCount("join-room-success", joined + 1, TIMEOUT), "加入应成功");

        int startA = a.count("start-game");
        a.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idA + "\",\"to\":\"" + idB + "\"}");
        b.send("{\"type\":\"info-ready\",\"content\":true,\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertTrue(a.awaitCount("start-game", startA + 1, TIMEOUT), "双方准备后应开局");

        Room room = SocketServer.roomMap.get(roomId);
        int invalidBefore = a.count("action-invalid");
        //A 直接发「同意和棋」，从未有任何人发起过和棋请求
        a.send("{\"type\":\"peace-agree\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\"}");
        assertTrue(a.awaitCount("action-invalid", invalidBefore + 1, TIMEOUT),
                "无对应请求的「同意」应被拒绝，实际收到：" + a.messages());
        assertEquals("playing", room.getState(), "被拒绝的和棋不能改变对局状态");
        assertEquals(0, room.getScoreId1() + room.getScoreId2(), "被拒绝的和棋不能产生比分");

        //正常流程仍然可用：B 发起和棋、A 同意，应当生效
        int peaceRestart = a.count("peace-restart");
        b.send("{\"type\":\"peace\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idB + "\",\"to\":\"" + idA + "\"}");
        assertNotNull(a.await("peace", TIMEOUT), "A 应收到和棋请求");
        a.send("{\"type\":\"peace-agree\",\"roomId\":\"" + roomId + "\",\"from\":\"" + idA
                + "\",\"to\":\"" + idB + "\"}");
        assertTrue(a.awaitCount("peace-restart", peaceRestart + 1, TIMEOUT), "确有请求时同意应生效");

        a.close();
        b.close();
    }

    /** 审查缺陷 5：超大请求体在进入业务逻辑前就被拒绝（避免一个请求打爆堆内存） */
    @Test
    @Order(6)
    void oversizedBodyRejected() {
        StringBuilder body = new StringBuilder("{\"token\":\"");
        while (body.length() < 9 * 1024 * 1024) {
            body.append("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        }
        body.append("\"}");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        boolean rejected;
        try{
            ResponseEntity<String> response = rest.exchange("/sessionCheck", HttpMethod.POST,
                    new HttpEntity<>(body.toString(), headers), String.class);
            //正常情况：过滤器在读取请求体之前就返回 413
            rejected = response.getStatusCodeValue() == 413;
        }catch(Exception e){
            //服务端提前拒绝并断开时，客户端可能直接收到连接重置——同样是"被拒绝"，不是把请求读进了内存
            rejected = true;
        }
        assertTrue(rejected, "超过 8MB 的请求体应被拒绝");

        //关键：拒绝之后服务仍然可用（没有因为大请求占满内存而崩溃）
        JSONObject alive = post("/sessionCheck", "{\"id\":\"1\",\"token\":\"x\"}");
        assertNotNull(alive, "拒绝超大请求后服务应继续响应正常请求");
    }

    // ==================== 辅助方法 ====================

    /**
     * 第二轮审查新增：头像只限制字节数不够，几 MB 的图可以解压成上亿像素，
     * 展示该头像的所有客户端都会因解码而吃满内存。这里用一张声明 5000×5000 的 PNG 验证会被拒。
     */
    @Test
    @Order(7)
    void oversizedImageDimensionsRejected() {
        String id = registerAndLogin("rvim");
        String image = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(fakePngHeader(5000, 5000));
        JSONObject response = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + tokens.get(id) + "\",\"image\":\"" + image + "\"}");
        assertEquals(400, response.getInt("code"), "超大尺寸图片应被业务校验拒绝：" + response);
        assertTrue(String.valueOf(response.getStr("message")).contains("尺寸过大"),
                "应给出尺寸超限的提示：" + response);

        //同一个人上传正常尺寸的图片仍然成功（校验只针对异常尺寸）
        String ok = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(fakePngHeader(256, 256));
        JSONObject accepted = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + tokens.get(id) + "\",\"image\":\"" + ok + "\"}");
        assertEquals(200, accepted.getInt("code"), "正常尺寸图片应可上传：" + accepted);
    }

    /**
     * 第二轮审查新增：尺寸校验不能依赖 ImageIO 的严格解析。
     * 这里用一张 JDK 解码器会报 "SOS before SOF" 但浏览器能正常显示的 1×1 JPEG，
     * 验证它仍可上传（不能误杀），同时验证声明超尺寸的 JPEG 头会被拒。
     */
    @Test
    @Order(9)
    void jpegSizeParsedWithoutImageIO() {
        String id = registerAndLogin("rvjp");
        String tolerated = "data:image/jpeg;base64," + JPEG_1X1_B64;
        JSONObject accepted = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + tokens.get(id) + "\",\"image\":\"" + tolerated + "\"}");
        assertEquals(200, accepted.getInt("code"), "结构不够规范但浏览器可显示的 JPEG 不应被误杀：" + accepted);

        String tooBig = "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(fakeJpegHeader(5000, 5000));
        JSONObject rejected = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + tokens.get(id) + "\",\"image\":\"" + tooBig + "\"}");
        assertEquals(400, rejected.getInt("code"), "超大尺寸 JPEG 应被拒：" + rejected);
        assertTrue(String.valueOf(rejected.getStr("message")).contains("尺寸过大"),
                "应给出尺寸超限的提示：" + rejected);

        //头部被截断的 PNG（只有 magic）无法解析尺寸，按损坏拒绝，避免畸形图入库
        String broken = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00});
        JSONObject bad = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + tokens.get(id) + "\",\"image\":\"" + broken + "\"}");
        assertEquals(400, bad.getInt("code"), "截断图片应被拒：" + bad);
    }

    /**
     * 第二轮审查新增：GIF / WebP 的宽高解析同样要覆盖（解析器已改为纯头部解析）。
     * GIF 用逻辑屏宽高，WebP 用 VP8X 画布宽高。
     */
    @Test
    @Order(10)
    void gifAndWebpDimensionsEnforced() {
        String id = registerAndLogin("rvgw");
        String token = tokens.get(id);

        String bigGif = "data:image/gif;base64,"
                + Base64.getEncoder().encodeToString(fakeGifHeader(5000, 5000));
        JSONObject gif = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + token + "\",\"image\":\"" + bigGif + "\"}");
        assertEquals(400, gif.getInt("code"), "超大尺寸 GIF 应被拒：" + gif);

        String okGif = "data:image/gif;base64,"
                + Base64.getEncoder().encodeToString(fakeGifHeader(128, 128));
        JSONObject gifOk = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + token + "\",\"image\":\"" + okGif + "\"}");
        assertEquals(200, gifOk.getInt("code"), "正常尺寸 GIF 应可上传：" + gifOk);

        String bigWebp = "data:image/webp;base64,"
                + Base64.getEncoder().encodeToString(fakeWebpHeader(5000, 5000));
        JSONObject webp = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + token + "\",\"image\":\"" + bigWebp + "\"}");
        assertEquals(400, webp.getInt("code"), "超大尺寸 WebP 应被拒：" + webp);

        String okWebp = "data:image/webp;base64,"
                + Base64.getEncoder().encodeToString(fakeWebpHeader(200, 200));
        JSONObject webpOk = post("/avatarUploadPost",
                "{\"id\":\"" + id + "\",\"token\":\"" + token + "\",\"image\":\"" + okWebp + "\"}");
        assertEquals(200, webpOk.getInt("code"), "正常尺寸 WebP 应可上传：" + webpOk);
    }

    /**
     * 第二轮审查新增：只按 Content-Length 判大小会被 chunked 绕过，
     * 这里用 chunked 发送 9MB，验证服务端按实际读取字节数拒绝。
     */
    @Test
    @Order(8)
    void chunkedOversizedBodyRejected() {
        boolean rejected;
        int status = -1;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/sessionCheck").openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            //不设置固定长度 → 走 Transfer-Encoding: chunked
            conn.setChunkedStreamingMode(8192);
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 'a');
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"token\":\"".getBytes());
                long written = 0;
                while (written < 9L * 1024 * 1024) {
                    os.write(chunk);
                    written += chunk.length;
                }
                os.write("\"}".getBytes());
                os.flush();
            }
            status = conn.getResponseCode();
            rejected = status == 413;
        } catch (Exception e) {
            //服务端读到上限即中断，客户端可能收到 Broken pipe / 连接重置，同样属于被拒绝
            rejected = true;
        }
        assertTrue(rejected, "chunked 方式的超大请求体应被拒绝，实际状态码=" + status);

        JSONObject alive = post("/sessionCheck", "{\"id\":\"1\",\"token\":\"x\"}");
        assertNotNull(alive, "拒绝 chunked 超大请求后服务应继续可用");
    }

    /**
     * 构造只有 PNG 头（签名 + IHDR + IEND）的字节，ImageIO 读尺寸不需要像素数据。
     */
    /** 一张 1×1 的 JPEG：JDK 的 ImageIO 会报 SOS before SOF，浏览器可以正常显示 */
    private static final String JPEG_1X1_B64 = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwcJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPDIzNP/AABEIAAEAAQMBIgACEQEDEQH/xAAfAAABBQEBAQEBAQAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/aAAwDAQACEQMRAD8A/v4ooooA/9k=";

    /** 构造只含 SOI + SOF0 的最小 JPEG 头，够解析宽高 */
    private byte[] fakeJpegHeader(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xC0);          //SOF0
        out.write(0x00);
        out.write(0x11);          //段长 17
        out.write(0x08);          //精度 8bit
        out.write((height >> 8) & 0xFF);
        out.write(height & 0xFF);
        out.write((width >> 8) & 0xFF);
        out.write(width & 0xFF);
        out.write(0x03);          //3 个分量
        for (int i = 0; i < 9; i++) {
            out.write(0x01);
        }
        return out.toByteArray();
    }

    /** 构造 GIF89a 头（逻辑屏宽高，小端） */
    private byte[] fakeGifHeader(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] sig = "GIF89a".getBytes();
        out.write(sig, 0, sig.length);
        out.write(width & 0xFF);
        out.write((width >> 8) & 0xFF);
        out.write(height & 0xFF);
        out.write((height >> 8) & 0xFF);
        out.write(0x00);   //packed
        out.write(0x00);   //背景色索引
        out.write(0x00);   //像素比
        out.write(0x3B);   //trailer
        return out.toByteArray();
    }

    /** 构造 WebP（VP8X 扩展格式）头，画布宽高各占 3 字节小端，存的是值减一 */
    private byte[] fakeWebpHeader(int width, int height) {
        byte[] b = new byte[30];
        byte[] riff = "RIFF".getBytes();
        System.arraycopy(riff, 0, b, 0, 4);
        byte[] webp = "WEBP".getBytes();
        System.arraycopy(webp, 0, b, 8, 4);
        byte[] vp8x = "VP8X".getBytes();
        System.arraycopy(vp8x, 0, b, 12, 4);
        b[16] = 0x0A;      //chunk size = 10
        int w = width - 1, h = height - 1;
        b[24] = (byte) (w & 0xFF);
        b[25] = (byte) ((w >> 8) & 0xFF);
        b[26] = (byte) ((w >> 16) & 0xFF);
        b[27] = (byte) (h & 0xFF);
        b[28] = (byte) ((h >> 8) & 0xFF);
        b[29] = (byte) ((h >> 16) & 0xFF);
        return b;
    }

    private byte[] fakePngHeader(int width, int height) {        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, 8);
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, width);
        writeInt(ihdr, height);
        ihdr.write(8);   //bit depth
        ihdr.write(2);   //color type: truecolor
        ihdr.write(0);   //compression
        ihdr.write(0);   //filter
        ihdr.write(0);   //interlace
        byte[] ihdrData = ihdr.toByteArray();
        writeInt(out, ihdrData.length);
        byte[] type = "IHDR".getBytes();
        out.write(type, 0, 4);
        out.write(ihdrData, 0, ihdrData.length);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdrData);
        writeInt(out, (int) crc.getValue());
        //IEND
        writeInt(out, 0);
        out.write(new byte[]{'I', 'E', 'N', 'D'}, 0, 4);
        CRC32 endCrc = new CRC32();
        endCrc.update(new byte[]{'I', 'E', 'N', 'D'});
        writeInt(out, (int) endCrc.getValue());
        return out.toByteArray();
    }

    private void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private String registerAndLogin(String prefix) {
        String login = prefix + String.format("%06d", RANDOM.nextInt(1000000));
        post("/registerPost", "{\"id\":\"" + login + "\",\"name\":\"" + login
                + "\",\"password\":\"" + PASSWORD + "\"}");
        JSONObject response = post("/loginPost", "{\"login\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
        JSONObject content = response.getJSONObject("content");
        assertNotNull(content, "登录应成功，实际响应：" + response);
        String id = String.valueOf(content.getInt("id"));
        tokens.put(id, content.getStr("token"));
        return id;
    }

    private JSONObject post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return JSONUtil.parseObj(response.getBody());
    }

    private String userJson(String id) {
        JSONObject user = new JSONObject();
        user.put("id", Integer.valueOf(id));
        user.put("name", "rv" + id);
        return user.toString();
    }

    private String lastOf(TestWsClient client, String type) {
        java.util.List<String> list = client.ofType(type);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }
}
