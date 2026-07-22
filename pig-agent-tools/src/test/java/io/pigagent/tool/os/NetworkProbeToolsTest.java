package io.pigagent.tool.os;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NetworkProbeTools}: DNS resolution and TCP port probing, exercised offline against a numeric
 * host and an in-process {@link ServerSocket} on loopback (no external network needed).
 */
class NetworkProbeToolsTest {

    private final NetworkProbeTools tools = new NetworkProbeTools();

    @Test
    void resolveHost_numericAddressResolvesToItself() {
        String out = tools.resolveHost("127.0.0.1");

        assertThat(out).contains("resolves to").contains("127.0.0.1");
    }

    @Test
    void resolveHost_unresolvableNameReports() {
        // .invalid is reserved (RFC 2606) to never resolve — deterministic offline.
        assertThat(tools.resolveHost("no.such.host.example.invalid")).startsWith("cannot resolve:");
    }

    @Test
    void resolveHost_blankIsCanonicalError() {
        assertThat(tools.resolveHost(" ")).startsWith("{\"error\":").contains("empty host");
    }

    @Test
    void checkPort_openPortReportsOpen() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();

            String out = tools.checkPort("127.0.0.1", String.valueOf(port), "2000");

            assertThat(out).contains("127.0.0.1:" + port).contains("OPEN");
        }
    }

    @Test
    void checkPort_closedPortReportsClosedOrTimeout() throws IOException {
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = server.getLocalPort();
        } // closed now — nothing listens on this port

        String out = tools.checkPort("127.0.0.1", String.valueOf(port), "500");

        assertThat(out).contains("127.0.0.1:" + port);
        assertThat(out).containsAnyOf("CLOSED", "TIMED OUT");
    }

    @Test
    void checkPort_invalidInputsAreCanonicalErrors() {
        assertThat(tools.checkPort(" ", "80", "")).startsWith("{\"error\":").contains("empty host");
        assertThat(tools.checkPort("127.0.0.1", "abc", "")).startsWith("{\"error\":").contains("invalid port");
        assertThat(tools.checkPort("127.0.0.1", "70000", "")).startsWith("{\"error\":").contains("out of range");
        assertThat(tools.checkPort("127.0.0.1", "0", "")).startsWith("{\"error\":").contains("out of range");
    }

    @Test
    void checkPort_toleratesOddTimeoutStrings() throws IOException {
        // backlog large enough to hold the several probes we make without accepting them
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            String p = String.valueOf(port);

            // blank → default, non-numeric → default, huge → clamped: none may throw.
            assertThat(tools.checkPort("127.0.0.1", p, "")).contains("OPEN");
            assertThat(tools.checkPort("127.0.0.1", p, "xyz")).contains("OPEN");
            assertThat(tools.checkPort("127.0.0.1", p, "999999")).contains("OPEN");
        }
    }
}
