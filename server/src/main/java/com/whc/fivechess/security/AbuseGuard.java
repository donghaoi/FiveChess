package com.whc.fivechess.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 滥用防护（状态全部保存在内存中，服务重启即清空；多实例部署需改用共享存储）。
 *
 * <p>覆盖四类限制：
 * <ol>
 *   <li>普通 HTTP 接口按 IP 的总速率上限（防爬虫/刷接口）；</li>
 *   <li>登录、注册按 IP 与按账号双维度限速，且账号连续失败达到阈值后临时锁定；</li>
 *   <li>WebSocket 单 IP 连接数上限（防单 IP 开大量连接耗尽内存）；</li>
 *   <li>同一用户单位时间内的聊天消息条数上限（超限丢弃并提示）。</li>
 * </ol>
 * 阈值均可通过 {@code fivechess.security.*} 配置项调整；测试可用静态 setter 调整或关闭（{@link #setEnforced(boolean)}）。
 */
@Component
public class AbuseGuard {

    /** 固定窗口计数器 */
    private static final class Window {
        private long windowStart = System.currentTimeMillis();
        private int count = 0;

        synchronized boolean tryAcquire(int limit, long windowMs) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                windowStart = now;
                count = 0;
            }
            if (limit <= 0) {
                return true;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }

        synchronized boolean isIdle(long now, long windowMs) {
            return count == 0 || (now - windowStart) >= windowMs;
        }
    }

    /** 账号连续失败与锁定信息 */
    private static final class FailInfo {
        private int consecutive = 0;
        private long lockedUntil = 0L;
        //最近一次失败时间：用于淘汰「失败一两次后不再登录」的条目。
        //只靠 consecutive==0 判断会漏掉这些条目，导致 map 只增不减。
        private long lastFailureAt = 0L;

        synchronized boolean locked(long now) {
            return now < lockedUntil;
        }

        synchronized long remainSeconds(long now) {
            long remain = lockedUntil - now;
            return remain <= 0 ? 0L : (remain + 999) / 1000;
        }

        synchronized void onFailure(int threshold, long lockMs, long now) {
            consecutive++;
            lastFailureAt = now;
            if (threshold > 0 && consecutive >= threshold) {
                lockedUntil = now + lockMs;
                consecutive = 0;
            }
        }

        synchronized void onSuccess() {
            consecutive = 0;
            lockedUntil = 0L;
            lastFailureAt = 0L;
        }

        synchronized boolean idle(long now, long windowMs) {
            if (now < lockedUntil) {
                return false;
            }
            long last = Math.max(lastFailureAt, lockedUntil);
            return now - last >= windowMs;
        }
    }

    // ===== 可配置阈值 =====
    /** 总开关，关闭后所有限制直接放行（供测试使用） */
    static volatile boolean enforced = true;
    static volatile int httpPerIpLimit = 600;
    static volatile long httpWindowMs = 60_000L;
    static volatile int loginIpLimit = 10;
    static volatile int loginAccountLimit = 10;
    static volatile long loginWindowMs = 60_000L;
    static volatile int loginLockThreshold = 5;
    static volatile long loginLockMs = 300_000L;
    static volatile int registerPerIpLimit = 10;
    static volatile int wsMaxPerIp = 20;
    static volatile int chatPerUserLimit = 20;
    static volatile long chatWindowMs = 10_000L;
    //私人房间密码尝试：同一账号单位时间内最多试几次。密码校验走 PBKDF2（约几十毫秒），
    //不限次数既可用于爆破，也会把 CPU 打满，因此单独限流。
    static volatile int roomPasswordTryLimit = 10;
    static volatile long roomPasswordWindowMs = 60_000L;
    //登录态探测（公开接口，不走 HTTP 总限流）的单 IP 上限：正常前端每分钟只有几次
    static volatile int sessionCheckPerIpLimit = 120;
    //登录失败记录容量上限：账号名由客户端任意指定，不设上限时可被大量不同账号名撑爆内存
    private static final int LOGIN_FAILURE_MAX = 50_000;

    // 计数器与状态（key 为 IP 或账号/用户 id）
    private final Map<String, Window> httpWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> loginIpWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> loginAccountWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> registerWindows = new ConcurrentHashMap<>();
    private final Map<String, FailInfo> loginFailures = new ConcurrentHashMap<>();
    private final Map<String, Integer> wsConnections = new ConcurrentHashMap<>();
    private final Map<String, Window> chatWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> roomPasswordWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> sessionCheckWindows = new ConcurrentHashMap<>();

    private ScheduledExecutorService cleaner;

    // ===== 配置注入（可环境变量覆盖）=====
    /** 总开关：false 时所有限制直接放行（本地压测/自动化测试可关闭） */
    @Value("${fivechess.security.enabled:true}")
    public void setEnabledValue(boolean value) {
        enforced = value;
        //启动日志：确认限流防护的生效状态（ASCII 标记，便于日志检索）
        System.out.println("[security] abuse-guard rate-limit enforced=" + value);
    }

    /**
     * 是否按转发头识别真实来源 IP。
     *
     * <p>取值：{@code auto} / {@code true} / {@code false}。{@code auto} 时由部署平台自动判定——
     * 检出 Render（{@code RENDER} 环境变量由平台注入）则视为「在 CDN 边缘之后」自动开启，
     * 否则保持关闭（直连部署不读任何请求头，客户端无法伪造来源）。</p>
     */
    @Value("${fivechess.security.trust-forwarded-for:auto}")
    public void setTrustForwardedFor(String raw) {
        Boolean explicit = parseTriState(raw);
        boolean edge = ClientIpResolver.detectEdgePlatform();
        boolean trust = explicit != null ? explicit : edge;
        ClientIpResolver.setTrustForwardedFor(trust);
        //把读到的原始值一起打出来：线上排查时最常卡在「我明明没配，怎么是 false」，
        //日志里直接看到 raw 就能确定是环境变量（含共享变量组 / Blueprint）还是代码问题
        System.out.println("[security] trust-forwarded-for=" + quote(raw) + " -> " + trust
                + (explicit == null ? " (auto: edge-platform=" + edge + ")" : " (configured: edge-platform=" + edge + ")"));
        if (explicit != null && !explicit && edge) {
            System.out.println("[security] WARNING: X-Forwarded-For trust is switched OFF manually while running on an"
                    + " edge platform. CF-Connecting-IP still resolves the client, but the X-Forwarded-For fallback"
                    + " is disabled - if the edge header ever goes missing, the proxy address gets recorded."
                    + " Remove FIVECHESS_TRUST_FORWARDED_FOR or set it to auto.");
        }
    }

    /**
     * 是否直接采信 Cloudflare 边缘写入的 {@code CF-Connecting-IP}。
     *
     * <p>取值：{@code auto} / {@code true} / {@code false}。{@code auto} 时与
     * {@link #setTrustForwardedFor(String)} 使用同一套平台判定。</p>
     *
     * <p>为什么需要它：Cloudflare（以及跑在 Cloudflare 上的 Render）会把真实客户端地址覆盖写入这个头，
     * 而它前面的 {@code X-Forwarded-For} 可能被追加了多层边缘地址。此时按「右起第 N 跳」取值会取到
     * Cloudflare 节点 IP（104.x / 172.6x 段），表现为「每个用户记下来的 IP 都不一样、而且不是自己的」。
     * 只有在后端<b>必定</b>经过该边缘时才可开启，否则客户端自带一个同名头即可冒充来源。</p>
     */
    @Value("${fivechess.security.trust-cloudflare-header:auto}")
    public void setTrustCloudflareHeader(String raw) {
        Boolean explicit = parseTriState(raw);
        boolean edge = ClientIpResolver.detectEdgePlatform();
        boolean trust = explicit != null ? explicit : edge;
        ClientIpResolver.setTrustCloudflareHeader(trust);
        System.out.println("[security] trust-cloudflare-header=" + quote(raw) + " -> " + trust
                + (explicit == null ? " (auto: edge-platform=" + edge + ")" : " (configured: edge-platform=" + edge + ")"));
    }

    /** 日志里回显配置原值（截断 + 去换行，避免被环境变量注入多行日志） */
    private static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length() && i < 64; i++) {
            char c = raw.charAt(i);
            sb.append(c == '\r' || c == '\n' || c == '\t' ? ' ' : c);
        }
        if (raw.length() > 64) {
            sb.append("...");
        }
        return sb.append('"').toString();
    }

    /** 解析 {@code auto|true|false}；{@code auto} 与无法识别的值都返回 null（交给平台判定） */
    private static Boolean parseTriState(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if (value.isEmpty() || "auto".equals(value)) {
            return null;
        }
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value) || "on".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value) || "0".equals(value) || "no".equals(value) || "off".equals(value)) {
            return Boolean.FALSE;
        }
        System.out.println("[security] 无法识别的开关值 \"" + raw + "\"，按 auto 处理");
        return null;
    }

    /**
     * 可信代理层数：X-Forwarded-For 从右起第几跳是真实客户端。
     * 只有 trust-forwarded-for=true 时才有意义；必须与实际代理层数一致——
     * 写大了会把「客户端可控的那一段」当成真实地址，等于放开伪造。
     */
    @Value("${fivechess.security.forwarded-for-hops:1}")
    public void setForwardedForHops(int hops) {
        ClientIpResolver.setForwardedForHops(hops);
    }

    /**
     * IPv6 封禁比对掩码位数（默认 /64）：运营商在同一线路上轮换隐私地址，
     * 逐字比对会失效；设为 128 则退化为精确匹配。
     */
    @Value("${fivechess.security.ban-ipv6-prefix-bits:64}")
    public void setBanIpv6PrefixBits(int bits) {
        ClientIpResolver.setIpv6PrefixBits(bits);
    }

    @Value("${fivechess.security.http.per-ip-limit:600}")
    public void setHttpPerIpLimitValue(int value) {
        httpPerIpLimit = value;
    }

    @Value("${fivechess.security.http.window-ms:60000}")
    public void setHttpWindowMsValue(long value) {
        httpWindowMs = value;
    }

    @Value("${fivechess.security.login.ip-limit:10}")
    public void setLoginIpLimitValue(int value) {
        loginIpLimit = value;
    }

    @Value("${fivechess.security.login.account-limit:10}")
    public void setLoginAccountLimitValue(int value) {
        loginAccountLimit = value;
    }

    @Value("${fivechess.security.login.window-ms:60000}")
    public void setLoginWindowMsValue(long value) {
        loginWindowMs = value;
    }

    @Value("${fivechess.security.login.lock-threshold:5}")
    public void setLoginLockThresholdValue(int value) {
        loginLockThreshold = value;
    }

    @Value("${fivechess.security.login.lock-ms:300000}")
    public void setLoginLockMsValue(long value) {
        loginLockMs = value;
    }

    @Value("${fivechess.security.register.per-ip-limit:10}")
    public void setRegisterPerIpLimitValue(int value) {
        registerPerIpLimit = value;
    }

    @Value("${fivechess.security.ws.max-connections-per-ip:20}")
    public void setWsMaxPerIpValue(int value) {
        wsMaxPerIp = value;
    }

    @Value("${fivechess.security.chat.per-user-limit:20}")
    public void setChatPerUserLimitValue(int value) {
        chatPerUserLimit = value;
    }

    @Value("${fivechess.security.chat.window-ms:10000}")
    public void setChatWindowMsValue(long value) {
        chatWindowMs = value;
    }

    @Value("${fivechess.security.room-password.try-limit:10}")
    public void setRoomPasswordTryLimitValue(int value) {
        roomPasswordTryLimit = value;
    }

    @Value("${fivechess.security.room-password.window-ms:60000}")
    public void setRoomPasswordWindowMsValue(long value) {
        roomPasswordWindowMs = value;
    }

    @Value("${fivechess.security.session-check.per-ip-limit:120}")
    public void setSessionCheckPerIpLimitValue(int value) {
        sessionCheckPerIpLimit = value;
    }

    @PostConstruct
    public void startCleaner() {
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "fc-abuse-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        //周期清理过期计数，避免 map 无界增长
        cleaner.scheduleWithFixedDelay(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stopCleaner() {
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        evict(httpWindows, now, Math.max(httpWindowMs, 60_000L));
        evict(loginIpWindows, now, Math.max(loginWindowMs, 60_000L));
        evict(loginAccountWindows, now, Math.max(loginWindowMs, 60_000L));
        evict(registerWindows, now, Math.max(httpWindowMs, 60_000L));
        evict(chatWindows, now, Math.max(chatWindowMs, 60_000L));
        evict(roomPasswordWindows, now, Math.max(roomPasswordWindowMs, 60_000L));
        evict(sessionCheckWindows, now, Math.max(httpWindowMs, 60_000L));
        sweepLoginFailures(now);
    }

    /** 清理已过期的登录失败记录（定时任务与容量兜底共用） */
    private void sweepLoginFailures(long now) {
        Iterator<Map.Entry<String, FailInfo>> it = loginFailures.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, FailInfo> entry = it.next();
            if (entry.getValue().idle(now, Math.max(loginLockMs, loginWindowMs))) {
                it.remove();
            }
        }
    }

    private void evict(Map<String, Window> map, long now, long windowMs) {
        Iterator<Map.Entry<String, Window>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isIdle(now, windowMs)) {
                it.remove();
            }
        }
    }

    /** 取窗口计数器；map 过大时先清理空闲项，防止被恶意 key 撑爆内存 */
    private Window windowOf(Map<String, Window> map, String key) {
        if (map.size() > 20_000) {
            evict(map, System.currentTimeMillis(), 60_000L);
        }
        return map.computeIfAbsent(key, k -> new Window());
    }

    private static String nz(String value) {
        return (value == null || value.isEmpty()) ? "unknown" : value;
    }

    // ===== HTTP 总速率 =====

    /** 普通 HTTP 接口准入：false 表示超出该 IP 的单位时间总请求上限 */
    public boolean allowHttp(String ip) {
        if (!enforced) {
            return true;
        }
        return windowOf(httpWindows, nz(ip)).tryAcquire(httpPerIpLimit, httpWindowMs);
    }

    // ===== 登录 / 注册 =====

    /** 登录准入：返回 null 表示允许，否则为拒绝提示文案 */
    public String loginBlockReason(String ip, String account) {
        if (!enforced) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (account != null && !account.isEmpty()) {
            FailInfo info = loginFailures.get(account);
            if (info != null && info.locked(now)) {
                return "该账号因连续登录失败已被临时锁定，请 " + info.remainSeconds(now) + " 秒后再试！";
            }
        }
        if (!windowOf(loginIpWindows, nz(ip)).tryAcquire(loginIpLimit, loginWindowMs)) {
            return "操作过于频繁，请稍后再试！";
        }
        if (account != null && !account.isEmpty()
                && !windowOf(loginAccountWindows, account).tryAcquire(loginAccountLimit, loginWindowMs)) {
            return "操作过于频繁，请稍后再试！";
        }
        return null;
    }

    /** 记录一次登录失败，连续失败达到阈值即临时锁定该账号 */
    public void recordLoginFailure(String account) {
        if (!enforced || account == null || account.isEmpty()) {
            return;
        }
        //容量兜底：账号名由客户端任意指定，先用时间淘汰；仍满则不再新增。
        //保留既有条目（含锁定状态）比清空更安全：清空会让攻击者顺势解除已生效的锁定。
        if (loginFailures.size() >= LOGIN_FAILURE_MAX) {
            sweepLoginFailures(System.currentTimeMillis());
            if (loginFailures.size() >= LOGIN_FAILURE_MAX) {
                return;
            }
        }
        loginFailures.computeIfAbsent(account, k -> new FailInfo())
                .onFailure(loginLockThreshold, loginLockMs, System.currentTimeMillis());
    }

    /** 登录成功即清除该账号的失败计数与锁定 */
    public void recordLoginSuccess(String account) {
        if (account == null || account.isEmpty()) {
            return;
        }
        FailInfo info = loginFailures.get(account);
        if (info != null) {
            info.onSuccess();
        }
    }

    public boolean isAccountLocked(String account) {
        if (!enforced || account == null || account.isEmpty()) {
            return false;
        }
        FailInfo info = loginFailures.get(account);
        return info != null && info.locked(System.currentTimeMillis());
    }

    /** 注册准入：false 表示超出该 IP 的单位时间注册上限 */
    public boolean allowRegister(String ip) {
        if (!enforced) {
            return true;
        }
        return windowOf(registerWindows, nz(ip)).tryAcquire(registerPerIpLimit, httpWindowMs);
    }

    // ===== WebSocket 单 IP 连接数 =====

    /** 连接准入：false 表示该 IP 的并发连接数已达上限（不计数） */
    public boolean enterWs(String ip) {
        if (ip == null || ip.isEmpty()) {
            return true;
        }
        final boolean[] allowed = {true};
        wsConnections.compute(ip, (k, v) -> {
            int current = (v == null ? 0 : v);
            if (enforced && current >= wsMaxPerIp) {
                allowed[0] = false;
                return current;
            }
            allowed[0] = true;
            return current + 1;
        });
        return allowed[0];
    }

    /** 连接关闭时释放该 IP 的一个连接计数 */
    public void leaveWs(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        wsConnections.compute(ip, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    }

    public int wsConnectionCount(String ip) {
        Integer v = wsConnections.get(ip);
        return v == null ? 0 : v;
    }

    // ===== 聊天频率 =====

    /** 聊天消息准入：false 表示该用户单位时间发言超限 */
    public boolean allowChat(String userId) {
        if (!enforced || userId == null || userId.isEmpty()) {
            return true;
        }
        return windowOf(chatWindows, userId).tryAcquire(chatPerUserLimit, chatWindowMs);
    }

    /**
     * 私人房间密码尝试准入：false 表示该账号单位时间内试错次数已达上限。
     * 密码校验是 PBKDF2，必须限次，否则可被用来爆破密码或打满 CPU。
     */
    public boolean allowRoomPasswordTry(String userId) {
        if (!enforced || userId == null || userId.isEmpty()) {
            return true;
        }
        return windowOf(roomPasswordWindows, userId).tryAcquire(roomPasswordTryLimit, roomPasswordWindowMs);
    }

    // ===== 公开探测接口 =====

    /**
     * 登录态探测准入：false 表示该 IP 单位时间内探测次数超限。
     *
     * /sessionCheck 不需要登录，为了让页面打开/切回前台/断线重连能立刻拿到结论，
     * 它被排除在 HTTP 总限流之外；但正因如此，任何人（无需账号）都能无限调用。
     * 这里给它单独一个宽松但有限的上限，防止被当成放大器刷。
     */
    public boolean allowSessionCheck(String ip) {
        if (!enforced) {
            return true;
        }
        return windowOf(sessionCheckWindows, nz(ip)).tryAcquire(sessionCheckPerIpLimit, httpWindowMs);
    }

    // ===== 测试辅助：调整阈值 / 关闭 / 清空 =====

    public static void setEnforced(boolean value) {
        enforced = value;
    }

    public static boolean isEnforced() {
        return enforced;
    }

    public static void setHttpPerIpLimit(int value) {
        httpPerIpLimit = value;
    }

    public static void setHttpWindowMs(long value) {
        httpWindowMs = value;
    }

    public static void setLoginIpLimit(int value) {
        loginIpLimit = value;
    }

    public static void setLoginAccountLimit(int value) {
        loginAccountLimit = value;
    }

    public static void setLoginWindowMs(long value) {
        loginWindowMs = value;
    }

    public static void setLoginLockThreshold(int value) {
        loginLockThreshold = value;
    }

    public static void setLoginLockMs(long value) {
        loginLockMs = value;
    }

    public static void setRegisterPerIpLimit(int value) {
        registerPerIpLimit = value;
    }

    public static void setWsMaxPerIp(int value) {
        wsMaxPerIp = value;
    }

    public static void setChatPerUserLimit(int value) {
        chatPerUserLimit = value;
    }

    public static void setChatWindowMs(long value) {
        chatWindowMs = value;
    }

    public static void setRoomPasswordTryLimit(int value) {
        roomPasswordTryLimit = value;
    }

    public static void setRoomPasswordWindowMs(long value) {
        roomPasswordWindowMs = value;
    }

    public static void setSessionCheckPerIpLimit(int value) {
        sessionCheckPerIpLimit = value;
    }

    /** 当前登录态探测上限，供测试还原现场 */
    public static int sessionCheckPerIpLimitForTest() {
        return sessionCheckPerIpLimit;
    }

    /** 清空全部计数与锁定状态（测试隔离用） */
    public void clearAll() {
        httpWindows.clear();
        loginIpWindows.clear();
        loginAccountWindows.clear();
        registerWindows.clear();
        loginFailures.clear();
        wsConnections.clear();
        chatWindows.clear();
        roomPasswordWindows.clear();
        sessionCheckWindows.clear();
    }
}