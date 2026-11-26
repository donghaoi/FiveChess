package com.whc.fivechess;

import com.whc.fivechess.security.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP 解析与归一化的单元测试（不起 Spring 上下文）。
 *
 * <p>覆盖两类最容易被绕过的点：</p>
 * <ul>
 *   <li>来源 IP 不能由客户端伪造：转发头必须按「可信代理层数」从右往左取；</li>
 *   <li>同一来源的不同写法必须归一到同一个比对键：IPv6 大小写/压缩、双栈 ::ffff:、
 *       以及同一 /64 内轮换的隐私地址。</li>
 * </ul>
 */
class ClientIpResolverTests {

    @AfterEach
    void restoreDefaults() {
        //这些是静态开关，必须复原，避免影响同 JVM 里的 Spring 测试
        ClientIpResolver.setTrustForwardedFor(false);
        ClientIpResolver.setTrustCloudflareHeader(false);
        ClientIpResolver.setForwardedForHops(1);
        ClientIpResolver.setIpv6PrefixBits(64);
    }

    @Test
    void normalizesIpv4AndRejectsJunk() {
        assertEquals("127.0.0.1", ClientIpResolver.normalizeLiteral(" 127.0.0.1 "));
        assertEquals("255.255.255.255", ClientIpResolver.normalizeLiteral("255.255.255.255"));

        assertNull(ClientIpResolver.normalizeLiteral(null), "null 不能变成 IP");
        assertNull(ClientIpResolver.normalizeLiteral(""), "空串不能变成 IP");
        assertNull(ClientIpResolver.normalizeLiteral("unknown"), "unknown 不能当 IP 使用");
        assertNull(ClientIpResolver.normalizeLiteral("localhost"), "主机名不能当 IP（也不应触发 DNS 解析）");
        assertNull(ClientIpResolver.normalizeLiteral("1.2.3"), "段数不足应拒绝");
        assertNull(ClientIpResolver.normalizeLiteral("300.1.1.1"), "越界应拒绝");
        assertNull(ClientIpResolver.normalizeLiteral("01.2.3.4"), "前导零会被不同解析器当成八进制，应拒绝");
        assertNull(ClientIpResolver.normalizeLiteral("1.2.3.4\n5.6.7.8"), "含换行应拒绝（防日志注入）");
    }

    @Test
    void stripsPortFromIpv4WrittenByProxies() {
        //部分代理会把 $remote_addr:$remote_port 原样写进 X-Forwarded-For。
        //整体丢弃会退化成代理 IP（所有用户共享一个 IP，封一个号牵连全站），所以要剥端口。
        assertEquals("1.2.3.4", ClientIpResolver.normalizeLiteral("1.2.3.4:80"));
        assertEquals("1.2.3.4", ClientIpResolver.normalizeLiteral("1.2.3.4:65535"));
        assertNull(ClientIpResolver.normalizeLiteral("1.2.3.4:70000"), "端口越界不该被当成合法地址");
        assertNull(ClientIpResolver.normalizeLiteral("1.2.3.4:abc"), "端口不是数字应拒绝");
        assertNull(ClientIpResolver.normalizeLiteral("1.2.3.4:80:90"), "多段冒号不是 IPv4:端口");
        assertNull(ClientIpResolver.normalizeLiteral("1.2.3:80"), "主机段非法仍要拒绝");
        //IPv6 不受影响
        assertEquals("2001:db8:0:0:0:0:0:1", ClientIpResolver.normalizeLiteral("2001:db8::1"));
    }

    @Test
    void normalizesIpv6VariantsToOneForm() {
        assertEquals("2001:db8:0:0:0:0:0:1", ClientIpResolver.normalizeLiteral("2001:DB8::1"));
        assertEquals("0:0:0:0:0:0:0:1", ClientIpResolver.normalizeLiteral("[::1]:8080"));
        assertTrue(ClientIpResolver.normalizeLiteral("fe80::1%eth0").endsWith(":0:0:0:1"), "网卡作用域应被去掉");
        assertNull(ClientIpResolver.normalizeLiteral("2001:db8::1::2"), "多个 :: 非法");
    }

    @Test
    void ipv4MappedIpv6BecomesPlainIpv4() {
        //双栈访问时同一个客户端可能以 ::ffff:1.2.3.4 或 1.2.3.4 出现，必须判成同一个来源
        assertEquals("192.168.1.7", ClientIpResolver.normalizeLiteral("::ffff:192.168.1.7"));
        assertEquals(ClientIpResolver.matchKey("1.2.3.4"), ClientIpResolver.matchKey("::ffff:1.2.3.4"));
        assertTrue(ClientIpResolver.sameSource("1.2.3.4", "::FFFF:1.2.3.4"));
    }

    @Test
    void ipv6MatchesByPrefixSoPrivacyAddressRotationCannotEvade() {
        String reg = "2001:db8:abcd:12:aaaa:bbbb:cccc:dddd";
        String rotated = "2001:db8:abcd:12:1111:2222:3333:4444";
        String otherLine = "2001:db8:abcd:13:1111:2222:3333:4444";

        assertTrue(ClientIpResolver.sameSource(reg, rotated), "同一 /64 内换地址仍应命中");
        assertFalse(ClientIpResolver.sameSource(reg, otherLine), "不同 /64 不应误伤");

        //可退化为精确匹配
        ClientIpResolver.setIpv6PrefixBits(128);
        assertFalse(ClientIpResolver.sameSource(reg, rotated), "精确模式下换地址即不再命中");
    }

    @Test
    void forwardedHeaderTakesRightmostHopNotClientSuppliedLeftmost() {
        ClientIpResolver.setTrustForwardedFor(true);
        ClientIpResolver.setForwardedForHops(1);

        //单层 Nginx：右起第 1 个是代理自己写的对端地址，左边 6.6.6.6 是客户端伪造的
        assertEquals("203.0.113.9",
                ClientIpResolver.fromForwarded("6.6.6.6, 203.0.113.9", null),
                "取最左边等于允许客户端伪造来源 IP");

        //两层代理：hops=2 才是真实客户端
        ClientIpResolver.setForwardedForHops(2);
        assertEquals("203.0.113.9",
                ClientIpResolver.fromForwarded("6.6.6.6, 203.0.113.9, 10.0.0.1", null));
        ClientIpResolver.setForwardedForHops(1);
        assertEquals("10.0.0.1",
                ClientIpResolver.fromForwarded("6.6.6.6, 203.0.113.9, 10.0.0.1", null),
                "hops=1 时取的是最近一跳（单层代理场景）");

        //非法值要跳过，落到 X-Real-IP
        assertEquals("198.51.100.5", ClientIpResolver.fromForwarded("unknown", "198.51.100.5"));
        assertNull(ClientIpResolver.fromForwarded("unknown", "not-an-ip"), "两个头都非法时不能编造 IP");
    }

    @Test
    void hopsAreClamped() {
        ClientIpResolver.setForwardedForHops(0);
        assertEquals(1, ClientIpResolver.getForwardedForHops(), "层数小于 1 按 1 处理");
        ClientIpResolver.setForwardedForHops(99);
        assertEquals(10, ClientIpResolver.getForwardedForHops(), "层数过大要收敛，避免取到客户端可控段");
        ClientIpResolver.setIpv6PrefixBits(0);
        assertEquals(128, ClientIpResolver.getIpv6PrefixBits(), "非法掩码退化为精确匹配");
    }

    @Test
    void cloudflareHeaderIsOnlyAcceptedAsSingleLiteral() {
        //边缘只写一个地址；出现逗号说明链路里有人手工追加，不能采信
        assertEquals("222.128.67.24", ClientIpResolver.fromCloudflareHeader(" 222.128.67.24 "));
        assertEquals("2001:db8:0:0:0:0:0:1", ClientIpResolver.fromCloudflareHeader("2001:db8::1"));
        assertNull(ClientIpResolver.fromCloudflareHeader("1.2.3.4, 5.6.7.8"), "多值必须拒绝");
        assertEquals("1.2.3.4", ClientIpResolver.fromCloudflareHeader("1.2.3.4:80"),
                "该头本不带端口，若出现则按既有的剥端口规则规范化，不能整体丢弃");
        assertNull(ClientIpResolver.fromCloudflareHeader("not-an-ip"));
        assertNull(ClientIpResolver.fromCloudflareHeader(""));
        assertNull(ClientIpResolver.fromCloudflareHeader(null));
    }

    @Test
    void edgeHeaderWinsSoProxyAddressIsNeverRecorded() {
        //线上现象：TCP 对端是 Cloudflare 节点（104.x / 172.6x），X-Forwarded-For 也被追加了节点地址，
        //此时只有 CF-Connecting-IP 是真实客户端，必须优先取它
        ClientIpResolver.setTrustForwardedFor(true);
        ClientIpResolver.setTrustCloudflareHeader(true);
        ClientIpResolver.setForwardedForHops(1);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.71.150.23");
        request.addHeader("X-Forwarded-For", "222.128.67.24, 172.71.150.23");
        request.addHeader("CF-Connecting-IP", "222.128.67.24");
        assertEquals("222.128.67.24", ClientIpResolver.resolve(request));

        //该头不可用时退回按层数解析，仍然不能落到边缘节点地址
        MockHttpServletRequest noEdgeHeader = new MockHttpServletRequest();
        noEdgeHeader.setRemoteAddr("172.71.150.23");
        noEdgeHeader.addHeader("X-Forwarded-For", "222.128.67.24, 203.0.113.9");
        ClientIpResolver.setForwardedForHops(2);
        assertEquals("222.128.67.24", ClientIpResolver.resolve(noEdgeHeader));
    }

    @Test
    void edgeHeaderIsIgnoredUnlessTrusted() {
        //直连部署不读任何请求头，否则客户端自带一个 CF-Connecting-IP 就能冒充来源
        ClientIpResolver.setTrustForwardedFor(false);
        ClientIpResolver.setTrustCloudflareHeader(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("127.0.0.1", ClientIpResolver.resolve(request));
    }
}
