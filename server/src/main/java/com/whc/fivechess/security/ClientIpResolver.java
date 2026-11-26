package com.whc.fivechess.security;

import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;

/**
 * 解析客户端真实 IP，并提供「封禁比对」用的归一化键。
 *
 * <p>取值顺序：</p>
 * <ol>
 *   <li>直连部署（默认）：只认 TCP 连接的远端地址 {@code getRemoteAddr()}，客户端伪造任何请求头都无效；</li>
 *   <li>CDN / 托管平台边缘（{@code trust-cloudflare-header=true}）：优先读 {@code CF-Connecting-IP}，
 *       该头由 Cloudflare 边缘（含 Render 这类跑在 Cloudflare 上的平台）<b>强制覆盖写入</b>，
 *       客户端自己填的值会被替换掉，因此比 X-Forwarded-For 可靠；</li>
 *   <li>反向代理之后（{@code fivechess.security.trust-forwarded-for=true}）：按可信代理层数
 *       {@code forwarded-for-hops} 从 X-Forwarded-For <b>右起</b>取第 N 个，再退 {@code X-Real-IP}。</li>
 * </ol>
 *
 * <p>为什么是右起而不是左起：Nginx 的 {@code $proxy_add_x_forwarded_for} 会把「客户端自带的值」
 * 原样保留在左边、把自己看到的对端地址追加在右边。若取最左边，客户端自己写一个
 * {@code X-Forwarded-For: 1.2.3.4} 就能完全冒充来源 IP（绕过单 IP 限流、绕过封禁）。
 * 取右起第 hops 个取到的是「最外层可信代理亲手写下的地址」，客户端无法伪造。</p>
 *
 * <p>所有对外暴露的 IP 都经过 {@link #normalizeLiteral(String)} 校验：只接受合法的 IPv4/IPv6
 * 字面量，非法值一律返回 {@code null}（不把任意字符串写库，避免日志/存储被污染）。</p>
 */
public final class ClientIpResolver {

    /** 解析不到合法 IP 时的占位值（调用方据此判断「未知来源」，不能当成某个具体 IP 去封禁） */
    public static final String UNKNOWN = "unknown";

    /** 是否信任代理转发的 X-Forwarded-For / X-Real-IP */
    private static volatile boolean trustForwardedFor = false;

    /**
     * 是否信任 Cloudflare 边缘写入的 {@code CF-Connecting-IP}。
     *
     * <p>只在「请求必定经过 Cloudflare 边缘」时才能开：此时该头由边缘覆盖写入，客户端伪造无效。
     * 若后端可被直连，客户端自己带一个 {@code CF-Connecting-IP: 1.2.3.4} 就能冒充来源，
     * 因此默认关闭，由部署平台自动识别或显式配置开启。</p>
     */
    private static volatile boolean trustCloudflareHeader = false;

    /** Cloudflare 边缘写入的客户端地址头（Cloudflare 对所有套餐都覆盖写，客户端无法伪造） */
    public static final String HEADER_CF_CONNECTING_IP = "CF-Connecting-IP";

    /** Render 平台注入的环境变量：存在即代表服务跑在 Render 边缘（其前置网络是 Cloudflare） */
    private static final String ENV_RENDER = "RENDER";

    /** 可信代理层数：X-Forwarded-For 从右往左数第几跳是真实客户端（单层 Nginx 为 1） */
    private static volatile int forwardedForHops = 1;

    /** IPv6 封禁比对的掩码位数：默认 /64（同一线路换隐私地址后仍能命中） */
    private static volatile int ipv6PrefixBits = 64;

    private ClientIpResolver() {
    }

    public static void setTrustForwardedFor(boolean trust) {
        trustForwardedFor = trust;
    }

    public static boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public static void setTrustCloudflareHeader(boolean trust) {
        trustCloudflareHeader = trust;
    }

    public static boolean isTrustCloudflareHeader() {
        return trustCloudflareHeader;
    }

    /**
     * 自动识别「服务是否跑在已知的 CDN / 托管平台边缘」，用于 {@code auto} 配置项。
     *
     * <p>目前识别 Render（{@code RENDER} 环境变量由平台注入，客户端无法影响）：Render 的入站流量
     * 前置网络是 Cloudflare，{@code getRemoteAddr()} 拿到的是 Cloudflare 边缘节点地址
     * （104.x / 172.6x-172.7x 段，会随节点变化），真实客户端地址只存在于
     * {@code CF-Connecting-IP} 与 {@code X-Forwarded-For} 里。此方法用于让这类部署零配置即正确。</p>
     */
    public static boolean detectEdgePlatform() {
        return envPresent(ENV_RENDER);
    }

    private static boolean envPresent(String name) {
        String value = System.getenv(name);
        return value != null && !value.trim().isEmpty();
    }

    /** 设置可信代理层数；小于 1 按 1 处理，大于 10 按 10 处理（防止配置错误把客户端可控段当真实地址） */
    public static void setForwardedForHops(int hops) {
        forwardedForHops = hops < 1 ? 1 : Math.min(hops, 10);
    }

    public static int getForwardedForHops() {
        return forwardedForHops;
    }

    /** 设置 IPv6 比对掩码位数；小于 1 或大于 128 按精确匹配（128）处理 */
    public static void setIpv6PrefixBits(int bits) {
        ipv6PrefixBits = (bits < 1 || bits > 128) ? 128 : bits;
    }

    public static int getIpv6PrefixBits() {
        return ipv6PrefixBits;
    }

    /**
     * 解析请求来源 IP。
     *
     * @return 合法的 IPv4/IPv6 字面量；解析不到时返回 {@link #UNKNOWN}（不返回 null，避免调用方出现空键）
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        //Cloudflare / Render 边缘：CF-Connecting-IP 由边缘覆盖写，最可靠，优先于 X-Forwarded-For。
        //该开关独立，不依赖 trust-forwarded-for——两种部署形态的取值方式本来就不同。
        if (trustCloudflareHeader) {
            String edge = fromCloudflareHeader(request.getHeader(HEADER_CF_CONNECTING_IP));
            if (edge != null) {
                return edge;
            }
        }
        if (trustForwardedFor) {
            String forwarded = fromForwarded(request.getHeader("X-Forwarded-For"),
                    request.getHeader("X-Real-IP"));
            if (forwarded != null) {
                return forwarded;
            }
        } else {
            warnIfForwardedHeadersIgnored(request);
        }
        String remote = normalizeLiteral(request.getRemoteAddr());
        return remote == null ? UNKNOWN : remote;
    }

    /** 是否已经提示过「接到转发头但未开启信任」，避免每个请求都打一行日志 */
    private static final java.util.concurrent.atomic.AtomicBoolean FORWARDED_IGNORED_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 部署自检：请求里带了转发头，但本服务被配置成「不信任转发头」。
     *
     * <p>这说明请求实际经过了代理（Nginx / CDN / 隧道 / 云负载均衡），而当前取的
     * {@code getRemoteAddr()} 是<b>最后一跳代理的地址</b>，不是用户真实公网 IP。
     * 后果有两个：所有用户共享同一个 IP（封一个号牵连全站），以及多节点代理场景下
     * 这个地址会在节点之间来回变——看起来就像「每次取到的 IP 都不一样」。</p>
     *
     * <p>日志分两行：第一行回显本次请求四个取值来源的实际内容，第二行按实际情况给出
     * 「该开哪个开关」的具体结论，一条日志就能定配置。只在第一次命中时打印，不会放大日志。</p>
     *
     * <p>日志内容一律用 ASCII：Java 8 在部分容器里 {@code file.encoding} 是 ANSI_X3.4-1968，
     * 中文会被写成问号，运维就看不到提示了。</p>
     */
    private static void warnIfForwardedHeadersIgnored(HttpServletRequest request) {
        if (FORWARDED_IGNORED_WARNED.get()) {
            return;
        }
        String xff = request.getHeader("X-Forwarded-For");
        String realIp = request.getHeader("X-Real-IP");
        String cfIp = request.getHeader(HEADER_CF_CONNECTING_IP);
        boolean hasForwarded = notBlank(xff) || notBlank(realIp) || notBlank(cfIp);
        if (!hasForwarded) {
            return;
        }
        if (!FORWARDED_IGNORED_WARNED.compareAndSet(false, true)) {
            return;
        }
        System.out.println("[security] forwarded headers ignored (trust switches are off): the recorded IP is"
                + " the last-hop proxy address, not the client."
                + " remoteAddr=" + safeLog(request.getRemoteAddr())
                + ", CF-Connecting-IP=" + safeLog(cfIp)
                + ", X-Forwarded-For=" + safeLog(xff)
                + ", X-Real-IP=" + safeLog(realIp));
        System.out.println("[security] " + configHint(cfIp, xff, realIp)
                + " Otherwise every user shares one proxy address: banning one account blocks everyone"
                + " behind that proxy, and the address changes whenever the proxy node changes.");
    }

    /** 按本次请求实际出现的头，给出该开哪个开关的结论（避免让运维自己猜） */
    private static String configHint(String cfIp, String xff, String realIp) {
        if (notBlank(cfIp)) {
            return "This request carries CF-Connecting-IP, so the chain goes through Cloudflare."
                    + " Set FIVECHESS_TRUST_CLOUDFLARE_HEADER=true (safe only while every request must pass"
                    + " Cloudflare); it is overwritten by the edge and cannot be forged by clients.";
        }
        if (notBlank(xff)) {
            int entries = xff.split(",").length;
            return "X-Forwarded-For has " + entries + " entr" + (entries == 1 ? "y" : "ies")
                    + " (entries are not necessarily proxy layers - count your own architecture)."
                    + " Set FIVECHESS_TRUST_FORWARDED_FOR=true and FIVECHESS_FORWARDED_FOR_HOPS=<real layers>"
                    + " (1 for a single nginx, 2 for CDN + nginx).";
        }
        return "Only X-Real-IP is present, set FIVECHESS_TRUST_FORWARDED_FOR=true.";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 日志里回显请求头时截断并去掉换行，避免被构造成多行日志（日志注入） */
    private static String safeLog(String raw) {
        if (raw == null) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 96));
        for (int i = 0; i < raw.length() && sb.length() < 96; i++) {
            char c = raw.charAt(i);
            sb.append(c == '\r' || c == '\n' || c == '\t' ? ' ' : c);
        }
        if (raw.length() > 96) {
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * 从 {@code CF-Connecting-IP} 取客户端地址（Cloudflare / Render 边缘）。
     *
     * <p>该头由边缘节点强制覆盖写入，客户端自己带的值会被替换，所以只要请求确实经过边缘就可靠。
     * 校验两点：</p>
     * <ul>
     *   <li>必须是一个合法 IP 字面量，否则返回 {@code null}（不采信任意字符串）；</li>
     *   <li>不允许出现逗号。Cloudflare 只写一个地址，出现多值说明有人在链路里手工追加，
     *       此时一律不采信，退回按 {@code X-Forwarded-For} 层数解析。</li>
     * </ul>
     *
     * @return 合法 IP 字面量；不可采信时返回 null
     */
    public static String fromCloudflareHeader(String cfConnectingIp) {
        if (cfConnectingIp == null) {
            return null;
        }
        String value = cfConnectingIp.trim();
        if (value.isEmpty() || value.indexOf(',') >= 0) {
            return null;
        }
        return normalizeLiteral(value);
    }

    /**
     * 从转发头里取真实客户端地址（Servlet 与 WebSocket 握手两条链路共用）。
     *
     * @param xForwardedFor X-Forwarded-For 头，形如 "客户端, 代理1, 代理2"
     * @param xRealIp       X-Real-IP 头（部分代理只设这个）
     * @return 合法 IP 字面量；都取不到时返回 null
     */
    public static String fromForwarded(String xForwardedFor, String xRealIp) {
        if (xForwardedFor != null && !xForwardedFor.trim().isEmpty()) {
            String[] parts = xForwardedFor.split(",");
            //右起第 hops 个：hops=1 时就是最右边那一项（可信代理自己追加的）
            int index = parts.length - forwardedForHops;
            if (index < 0) {
                index = 0;
            }
            //个别代理会写入 "unknown" 之类的非法值，向左继续找一个合法的，找不到再看 X-Real-IP
            for (int i = index; i >= 0; i--) {
                String ip = normalizeLiteral(parts[i]);
                if (ip != null) {
                    return ip;
                }
            }
        }
        return normalizeLiteral(xRealIp);
    }

    /**
     * 校验并规范化单个 IP 字面量。
     *
     * <p>规范化内容：去首尾空白、去掉 {@code [::1]:8080} 的方括号与端口、去掉 {@code fe80::1%eth0}
     * 的网卡作用域、IPv6 统一小写并补全为 8 段、{@code ::ffff:1.2.3.4} 还原为 {@code 1.2.3.4}
     * （同一客户端经双栈访问时两种写法必须判成同一个 IP）。</p>
     *
     * @return 规范化后的字面量；不是合法 IP 时返回 null
     */
    public static String normalizeLiteral(String raw) {
        if (raw == null) {
            return null;
        }
        String ip = raw.trim();
        if (ip.isEmpty() || ip.length() > 64) {
            return null;
        }
        //[::1] 或 [::1]:54321
        if (ip.charAt(0) == '[') {
            int end = ip.indexOf(']');
            if (end < 0) {
                return null;
            }
            ip = ip.substring(1, end);
        }
        //fe80::1%eth0 / fe80::1%25eth0
        int zone = ip.indexOf('%');
        if (zone >= 0) {
            ip = ip.substring(0, zone);
        }
        ip = ip.trim();
        //1.2.3.4:5678：部分代理会把「地址:端口」原样写进 X-Forwarded-For（例如把
        //$remote_addr:$remote_port 直接拼进头里）。标准写法不含端口，但遇到这种值应当剥掉端口，
        //而不是整体丢弃——丢弃会让解析回退到直连地址（代理 IP），结果是所有用户共享同一个 IP，
        //封一个号就连带全站。
        int singleColon = ip.indexOf(':');
        if (singleColon > 0 && ip.indexOf(':', singleColon + 1) < 0) {
            String host = ip.substring(0, singleColon);
            String port = ip.substring(singleColon + 1);
            if (isValidPort(port) && isIpv4Literal(host)) {
                return host;
            }
        }
        if (isIpv4Literal(ip)) {
            return ip;
        }
        if (ip.indexOf(':') < 0) {
            //既不是 IPv4 也不含冒号：主机名、端口、"unknown" 等一律拒绝（避免触发 DNS 解析）
            return null;
        }
        if (!isIpv6Chars(ip)) {
            return null;
        }
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length == 16) {
                if (isIpv4Mapped(bytes)) {
                    return toIpv4(bytes, 12);
                }
                return formatIpv6(bytes);
            }
            if (bytes.length == 4) {
                return toIpv4(bytes, 0);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * 封禁比对键：把「同一个来源」归一到同一个字符串。
     *
     * <ul>
     *   <li>IPv4：地址本身（精确匹配）；</li>
     *   <li>IPv6：前 {@code ipv6PrefixBits} 位（默认 /64）。运营商会给同一线路分配一整段
     *       /64 并在其中轮换「隐私地址」，逐字比对会在几分钟后失效，按前缀比对才拦得住。</li>
     * </ul>
     *
     * @return 比对键（带 v4:/v6: 前缀，避免两种协议族撞车）；非法 IP 返回 null
     */
    public static String matchKey(String ip) {
        String literal = normalizeLiteral(ip);
        if (literal == null) {
            return null;
        }
        if (literal.indexOf(':') < 0) {
            return "v4:" + literal;
        }
        try {
            byte[] bytes = InetAddress.getByName(literal).getAddress();
            if (bytes.length != 16) {
                return "v4:" + literal;
            }
            int bits = ipv6PrefixBits;
            int bytesUsed = (bits + 7) / 8;
            StringBuilder sb = new StringBuilder(2 + bytesUsed * 2 + 4);
            sb.append("v6:");
            for (int i = 0; i < bytesUsed; i++) {
                sb.append(String.format("%02x", bytes[i] & 0xff));
            }
            sb.append('/').append(bits);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 两个地址是否属于同一个被禁止的来源（IPv4 精确、IPv6 按前缀） */
    public static boolean sameSource(String a, String b) {
        String ka = matchKey(a);
        return ka != null && ka.equals(matchKey(b));
    }

    // ==================== 内部工具 ====================

    private static boolean isIpv4Literal(String ip) {
        int dots = 0;
        int partLength = 0;
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            if (c == '.') {
                if (partLength == 0 || partLength > 3) {
                    return false;
                }
                dots++;
                partLength = 0;
            } else if (c >= '0' && c <= '9') {
                //拒绝 01.2.3.4 这类写法：不同解析器会当成八进制，导致同一个地址出现两种键
                if (partLength == 0 && c == '0' && i + 1 < ip.length() && ip.charAt(i + 1) != '.') {
                    return false;
                }
                partLength++;
                if (partLength > 3) {
                    return false;
                }
            } else {
                return false;
            }
        }
        if (dots != 3 || partLength == 0 || partLength > 3) {
            return false;
        }
        for (String part : ip.split("\\.")) {
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /** IPv6 只允许十六进制数字、冒号和点（内嵌 IPv4 写法） */
    private static boolean isIpv6Chars(String ip) {
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != ':' && c != '.') {
                return false;
            }
        }
        return true;
    }

    /** 是否一个合法端口号（1-65535，纯十进制），用于识别「地址:端口」写法 */
    private static boolean isValidPort(String text) {
        if (text == null || text.isEmpty() || text.length() > 5) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        int port = Integer.parseInt(text);
        return port >= 1 && port <= 65535;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static String toIpv4(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) + "." + (bytes[offset + 1] & 0xff) + "."
                + (bytes[offset + 2] & 0xff) + "." + (bytes[offset + 3] & 0xff);
    }

    /** 统一成小写、无前导零的 8 段形式，保证同一地址只有一种写法 */
    private static String formatIpv6(byte[] bytes) {
        StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                sb.append(':');
            }
            int high = bytes[i * 2] & 0xff;
            int low = bytes[i * 2 + 1] & 0xff;
            sb.append(Integer.toHexString((high << 8) | low));
        }
        return sb.toString();
    }
}
