package io.pigagent.tool.webfetch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SSRF egress guard for the web-fetch tool. Pure, side-effect-free, offline-testable.
 *
 * <p>Blocks a URL when: the scheme is not {@code http}/{@code https}; the host is missing; an
 * optional non-empty host allowlist does not contain the host; or <em>any</em> IP the host resolves
 * to is loopback / any-local / link-local (incl. the {@code 169.254.169.254} cloud metadata
 * endpoint) / site-local private ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) / IPv6 ULA
 * ({@code fc00::/7}) / multicast. Judging on the <em>resolved IP</em> — not the literal host string
 * — is what defeats decimal-IP, {@code [::1]}, and DNS-points-at-internal bypasses.
 */
public final class SsrfGuard {

    private SsrfGuard() {
    }

    /**
     * @param url          the URL the tool is about to fetch
     * @param allowedHosts optional host allowlist; empty/null means "no allowlist, IP guard only"
     * @return {@code null} if the URL is allowed, otherwise a short reason why it is blocked
     */
    public static String checkBlocked(String url, List<String> allowedHosts) {
        if (url == null || url.isBlank()) {
            return "empty url";
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            return "invalid url";
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "unsupported scheme";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "missing host";
        }
        if (allowedHosts != null && !allowedHosts.isEmpty()) {
            Set<String> allow = allowedHosts.stream()
                    .map(SsrfGuard::normalizeHost).collect(Collectors.toSet());
            if (!allow.contains(normalizeHost(host))) {
                return "host not in allowlist";
            }
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(stripBrackets(host));
        } catch (UnknownHostException e) {
            return "unresolvable host";
        }
        for (InetAddress addr : addresses) {
            if (isBlockedAddress(addr)) {
                return "destination resolves to a private or reserved address";
            }
        }
        return null;
    }

    /** True if the address is loopback / any-local / link-local / site-local / ULA / multicast. */
    static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        // IPv6 Unique Local Address fc00::/7 — isSiteLocalAddress() does not cover it.
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static String normalizeHost(String host) {
        String h = stripBrackets(host).toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    private static String stripBrackets(String host) {
        if (host.length() > 1 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
