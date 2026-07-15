package io.pigagent.plugin.builtin.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSRF 出口守卫（纯函数、离线）：基于解析后的 IP 拒绝回环/私网/链路本地/ULA/multicast，
 * 拒非 http(s) scheme，防十进制IP/`[::1]` 绕过；可选 allowed-hosts 白名单。
 * 用字面 IP（不触发 DNS）保证确定性与离线。
 */
class SsrfGuardTest {

    @Test
    void blocksLoopbackV4() {
        assertThat(SsrfGuard.checkBlocked("http://127.0.0.1/", List.of())).isNotNull();
    }

    @Test
    void blocksLoopbackV6Bracketed() {
        assertThat(SsrfGuard.checkBlocked("http://[::1]/", List.of())).isNotNull();
    }

    @Test
    void blocksLocalhost() {
        assertThat(SsrfGuard.checkBlocked("http://localhost/", List.of())).isNotNull();
    }

    @Test
    void blocksPrivateRanges() {
        assertThat(SsrfGuard.checkBlocked("http://10.0.0.5/", List.of())).isNotNull();
        assertThat(SsrfGuard.checkBlocked("http://172.16.0.1/", List.of())).isNotNull();
        assertThat(SsrfGuard.checkBlocked("http://192.168.1.1/", List.of())).isNotNull();
    }

    @Test
    void blocksCloudMetadataLinkLocal() {
        assertThat(SsrfGuard.checkBlocked("http://169.254.169.254/latest/meta-data/", List.of()))
                .isNotNull();
    }

    @Test
    void blocksMulticast() {
        assertThat(SsrfGuard.checkBlocked("http://224.0.0.1/", List.of())).isNotNull();
    }

    @Test
    void blocksNonHttpScheme() {
        assertThat(SsrfGuard.checkBlocked("ftp://example.com/", List.of())).isNotNull();
        assertThat(SsrfGuard.checkBlocked("file:///etc/passwd", List.of())).isNotNull();
    }

    @Test
    void blocksDecimalIpBypass() {
        // 2130706433 == 127.0.0.1 ; must not slip through as a "public hostname"
        assertThat(SsrfGuard.checkBlocked("http://2130706433/", List.of())).isNotNull();
    }

    @Test
    void allowsPublicLiteralIp_whenNoAllowlist() {
        assertThat(SsrfGuard.checkBlocked("http://8.8.8.8/", List.of())).isNull();
    }

    @Test
    void allowlist_blocksHostNotListed() {
        assertThat(SsrfGuard.checkBlocked("http://1.1.1.1/", List.of("8.8.8.8"))).isNotNull();
    }

    @Test
    void allowlist_allowsListedPublicHost() {
        assertThat(SsrfGuard.checkBlocked("http://8.8.8.8/", List.of("8.8.8.8"))).isNull();
    }

    @Test
    void allowlist_stillBlocksListedButPrivateHost() {
        // even if allowlisted, a private/loopback resolution is still refused (defense in depth)
        assertThat(SsrfGuard.checkBlocked("http://127.0.0.1/", List.of("127.0.0.1"))).isNotNull();
    }
}
