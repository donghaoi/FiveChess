package com.whc.fivechess;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.whc.fivechess.component.TokenStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录令牌闲置过期测试：
 * 超过设定时长未使用即失效（reason=expired），期间有请求/心跳则自动续期，配置为 0 表示永不过期，
 * 以及 /sessionCheck 能把「过期」与「重启/顶号/退出」区分开，供前端给出准确提示。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenExpiryTests {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TokenStore tokenStore;

    private static final String LOGIN = "tkt" + String.format("%06d", new Random().nextInt(1000000));
    private static final String PASSWORD = "tkt123456";

    /** 用例开始前的真实配置，结束后必须还原，避免影响其它测试类 */
    private long originalTtl;

    @BeforeAll
    void rememberTtl(){
        originalTtl = tokenStore.getTtlMillis();
    }

    @AfterEach
    void restoreTtlAfterEach(){
        tokenStore.setTtlMillis(originalTtl);
    }

    @AfterAll
    void cleanUp(){
        tokenStore.setTtlMillis(originalTtl);
        try{
            jdbc.update("delete from user where login = ?", LOGIN);
        }catch(Exception ignored){
        }
    }

    private JSONObject post(String path, String body){
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCodeValue(), "HTTP 状态码应为 200：" + path);
        assertNotNull(response.getBody(), "响应体不应为空：" + path);
        return JSONUtil.parseObj(response.getBody());
    }

    @Test
    void 闲置超时后令牌失效且原因可区分() throws Exception{
        tokenStore.setTtlMillis(150);
        String token = tokenStore.issue("tk_idle");
        assertTrue(tokenStore.validate("tk_idle", token), "刚下发的令牌应有效");
        assertNull(tokenStore.invalidReason("tk_idle", token), "刚下发的令牌不应有失效原因");

        Thread.sleep(300);
        assertFalse(tokenStore.validate("tk_idle", token), "超过闲置时长后应失效");
        assertEquals(TokenStore.REASON_EXPIRED, tokenStore.invalidReason("tk_idle", token),
                "失效原因应为 expired，便于前端提示「长时间未使用」");
    }

    @Test
    void 期间有使用则自动续期() throws Exception{
        tokenStore.setTtlMillis(400);
        String token = tokenStore.issue("tk_touch");
        //总计 600ms 已超过 400ms，但中间每隔 200ms 使用一次，因此始终有效
        for(int i = 0; i < 3; i++){
            Thread.sleep(200);
            assertTrue(tokenStore.validate("tk_touch", token), "第 " + (i + 1) + " 次使用时应仍然有效");
        }
        assertNull(tokenStore.invalidReason("tk_touch", token));
    }

    @Test
    void 心跳续期同样生效() throws Exception{
        tokenStore.setTtlMillis(300);
        String token = tokenStore.issue("tk_beat");
        Thread.sleep(200);
        //页面开着时每 10 秒一次心跳，这里直接调用续期入口
        tokenStore.touch("tk_beat");
        Thread.sleep(200);
        assertTrue(tokenStore.validate("tk_beat", token), "心跳续期后不应因闲置过期");
    }

    @Test
    void 配置为0时永不过期() throws Exception{
        tokenStore.setTtlMillis(0);
        String token = tokenStore.issue("tk_forever");
        Thread.sleep(200);
        assertTrue(tokenStore.validate("tk_forever", token), "关闭闲置过期后令牌应一直有效");
        assertNull(tokenStore.invalidReason("tk_forever", token));
    }

    @Test
    void 会话探测接口返回过期原因(){
        //注册并登录拿到真实令牌
        JSONObject reg = post("/registerPost",
                "{\"id\":\"" + LOGIN + "\",\"name\":\"令牌过期测试\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, reg.getInt("code"), "注册应成功：" + reg);
        JSONObject login = post("/loginPost",
                "{\"login\":\"" + LOGIN + "\",\"password\":\"" + PASSWORD + "\"}");
        JSONObject content = login.getJSONObject("content");
        assertNotNull(content, "登录响应应带 content：" + login);
        String userId = String.valueOf(content.get("id"));
        String token = content.getStr("token");
        assertNotNull(token, "登录应下发令牌");

        //令牌刚下发：探测应回答有效
        JSONObject ok = post("/sessionCheck",
                "{\"id\":\"" + userId + "\",\"token\":\"" + token + "\"}").getJSONObject("content");
        assertTrue(ok.getBool("valid"), "刚登录的令牌应探测为有效：" + ok);
        assertEquals(TokenStore.REASON_OK, ok.getStr("reason"));

        //把闲置时长调到极短并等待超时：探测应回答过期，且原因与「重启/顶号/退出」区分开
        tokenStore.setTtlMillis(100);
        try{
            Thread.sleep(250);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
        }
        JSONObject expired = post("/sessionCheck",
                "{\"id\":\"" + userId + "\",\"token\":\"" + token + "\"}").getJSONObject("content");
        assertFalse(expired.getBool("valid"), "闲置超时后探测应回答无效：" + expired);
        assertEquals(TokenStore.REASON_EXPIRED, expired.getStr("reason"),
                "原因应为 expired（前端据此提示「登录状态已过期」）：" + expired);
    }
}
