package com.whc.fivechess.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录令牌存储。
 * 登录成功后为账号下发令牌，WebSocket 首条鉴权消息与对局数据接口都需要携带；
 * 同一账号重新登录会覆盖旧令牌（与"单点在线"保持一致）。
 *
 * 令牌形如「<本次启动标识>.<随机串>」：启动标识在服务启动时随机生成，
 * 因此可以据此把「服务重启导致会话失效」与「账号在别处登录」区分开，
 * 前端就能给出准确的提示文案，而不是笼统的「登录状态已失效」。
 *
 * 闲置过期：每条登录记录都带最近使用时间，HTTP 请求、WebSocket 鉴权与心跳都会续期；
 * 超过 fivechess.token.ttl-hours（默认 12 小时）未使用即失效，需要重新登录。
 * 页面开着（心跳每 10 秒一次）会持续续期，所以正常情况下只有长时间不登录才会失效。
 */
@Component
public class TokenStore {

    /** 令牌有效 */
    public static final String REASON_OK = "ok";
    /** 令牌由上一个服务实例下发（服务重启/发布更新导致会话失效） */
    public static final String REASON_RESTART = "restart";
    /** 账号在别处登录，旧令牌已被新令牌覆盖 */
    public static final String REASON_SUPERSEDED = "superseded";
    /** 服务端没有该账号的令牌（已退出登录、令牌被注销） */
    public static final String REASON_LOGGED_OUT = "loggedout";
    /** 请求未携带令牌 */
    public static final String REASON_MISSING = "missing";
    /** 令牌闲置超时（长时间未使用），需要重新登录 */
    public static final String REASON_EXPIRED = "expired";

    /** 默认闲置有效期：12 小时 */
    private static final long DEFAULT_TTL_MILLIS = 12L * 60 * 60 * 1000;

    /** 本次服务启动标识：8 位随机十六进制，写在每枚令牌的前缀里 */
    private final String bootId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    /**
     * 反向索引：令牌值 → 账号id。
     *
     * 没有它时 userIdOf / invalidReason(只带令牌) 必须遍历整个 tokens（O(n)），
     * 而 /sessionCheck 是不需要登录、且被排除在 HTTP 限流之外的公开接口：
     * 任何人拿一次响应里的 bootId 就能构造「bootId.xxx」令牌无限请求，
     * 每次都触发一次全表遍历，在线账号多时足以把 CPU 打满。
     * 用这个索引把上述两条路径降为 O(1)。
     */
    private final Map<String, String> tokenOwners = new ConcurrentHashMap<>();

    /** 上次全表清理时间：避免每次登录都遍历全部账号（登录是高频路径） */
    private volatile long lastPurgeAt = 0L;
    /** 全表清理的最小间隔：一分钟一次足够，过期条目多留一会儿不影响正确性 */
    private static final long PURGE_INTERVAL_MS = 60_000L;

    /** 闲置有效期（毫秒），0 表示永不过期；见 fivechess.token.ttl-hours */
    private volatile long ttlMillis = DEFAULT_TTL_MILLIS;

    /** 一条登录记录：令牌本身 + 最近使用时间（用于判定闲置过期） */
    private static final class Entry {
        private final String value;
        private final long issuedAt;
        private volatile long lastUsedAt;

        private Entry(String value, long now){
            this.value = value;
            this.issuedAt = now;
            this.lastUsedAt = now;
        }
    }

    public String getBootId(){
        return bootId;
    }

    /**
     * 闲置有效期（小时）：超过该时长未使用即失效，0 表示永不过期
     */
    @Value("${fivechess.token.ttl-hours:12}")
    public void setTtlHours(long hours){
        this.ttlMillis = hours <= 0 ? 0L : hours * 60 * 60 * 1000L;
    }

    public long getTtlMillis(){
        return ttlMillis;
    }

    /**
     * 直接设置闲置有效期（毫秒），供测试使用
     */
    public void setTtlMillis(long ms){
        this.ttlMillis = ms < 0 ? 0L : ms;
    }

    /**
     * 下发新令牌，旧令牌立即失效
     */
    public String issue(String userId){
        if(userId==null){
            return null;
        }
        purgeExpiredThrottled();
        String token = bootId + "." + UUID.randomUUID().toString().replace("-", "");
        Entry previous = tokens.put(userId, new Entry(token, System.currentTimeMillis()));
        //同一账号重新登录：旧令牌立即失效，反向索引也要跟着换，避免旧令牌还能反查到账号
        if(previous != null){
            tokenOwners.remove(previous.value);
        }
        tokenOwners.put(token, userId);
        return token;
    }

    /**
     * 校验令牌是否属于该账号且仍然有效；校验通过即刷新最近使用时间（闲置过期从此刻重新计算）
     */
    public boolean validate(String userId, String token){
        if(userId==null || token==null){
            return false;
        }
        Entry entry = tokens.get(userId);
        if(entry==null || !entry.value.equals(token) || isExpired(entry)){
            return false;
        }
        entry.lastUsedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 续期：只刷新最近使用时间，不做其它判断（供 WebSocket 心跳调用，页面开着就不会闲置过期）
     */
    public void touch(String userId){
        if(userId==null){
            return;
        }
        Entry entry = tokens.get(userId);
        if(entry==null || isExpired(entry)){
            return;
        }
        entry.lastUsedAt = System.currentTimeMillis();
    }

    /**
     * 通过令牌反查账号id，令牌无效（含已过期）时返回 null。
     * 走反向索引，O(1)：该路径会被公开接口调用，不能是全表遍历。
     */
    public String userIdOf(String token){
        if(token==null || token.isEmpty()){
            return null;
        }
        String userId = tokenOwners.get(token);
        if(userId == null){
            return null;
        }
        Entry entry = tokens.get(userId);
        if(entry == null || !entry.value.equals(token) || isExpired(entry)){
            return null;
        }
        return userId;
    }

    /**
     * 注销账号的令牌
     */
    public void revoke(String userId){
        if(userId==null){
            return;
        }
        Entry removed = tokens.remove(userId);
        if(removed != null){
            tokenOwners.remove(removed.value);
        }
    }

    public void clear(){
        tokens.clear();
        tokenOwners.clear();
    }

    /** 带节流的过期清理：登录是高频路径，不能每次登录都遍历全部账号 */
    private void purgeExpiredThrottled(){
        long now = System.currentTimeMillis();
        if(now - lastPurgeAt < PURGE_INTERVAL_MS){
            return;
        }
        lastPurgeAt = now;
        purgeExpired();
    }

    /**
     * 清理已过期的登录记录，避免长期运行后堆积（定时/登录时节流触发）
     */
    public int purgeExpired(){
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> it = tokens.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String, Entry> item = it.next();
            if(isExpired(item.getValue())){
                it.remove();
                tokenOwners.remove(item.getValue().value);
                removed++;
            }
        }
        return removed;
    }

    private boolean isExpired(Entry entry){
        long ttl = ttlMillis;
        return ttl > 0 && System.currentTimeMillis() - entry.lastUsedAt > ttl;
    }

    /**
     * 判断令牌为什么不可用（用于给前端返回精确的失效原因）。
     *
     * @return null 表示令牌有效；否则返回 {@link #REASON_RESTART}、
     *         {@link #REASON_SUPERSEDED}、{@link #REASON_LOGGED_OUT}、
     *         {@link #REASON_MISSING}、{@link #REASON_EXPIRED}
     */
    public String invalidReason(String userId, String token){
        if(token==null || token.isEmpty()){
            return REASON_MISSING;
        }
        // 前缀不是本次启动标识：令牌是上一个服务实例下发的，说明服务重启过
        if(!token.startsWith(bootId + ".")){
            return REASON_RESTART;
        }
        // 未指定账号（例如只凭令牌访问对局数据）：能反查到账号就认为有效
        if(userId==null || userId.trim().isEmpty()){
            String owner = tokenOwners.get(token);
            if(owner == null){
                return REASON_LOGGED_OUT;
            }
            Entry found = tokens.get(owner);
            if(found == null || !found.value.equals(token)){
                return REASON_LOGGED_OUT;
            }
            return isExpired(found) ? REASON_EXPIRED : null;
        }
        Entry current = tokens.get(userId.trim());
        if(current==null){
            // 本次启动内下发过令牌，但现在已不存在：退出登录/被注销
            return REASON_LOGGED_OUT;
        }
        if(!current.value.equals(token)){
            return REASON_SUPERSEDED;
        }
        return isExpired(current) ? REASON_EXPIRED : null;
    }
}
