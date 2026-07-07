package io.pigagent.cli;

import io.pigagent.config.PigAgentConfig;
import io.pigagent.web.WebConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone entry point for the local Web console — an INDEPENDENT process from the CLI. The CLI
 * ({@link PigAgentCli}) never starts the Web server; you launch this separately. Both share the same
 * on-disk workspace ({@code ~/.pig-agent/workspace/}) but run their own in-memory runtime.
 *
 * <p>Run: {@code mvn exec:java -pl pig-agent-cli -Dexec.mainClass=io.pigagent.cli.WebLauncher}.
 * Host/port come from {@code web.host}/{@code web.port} in {@code application.yaml} (default
 * {@code 127.0.0.1:7317}); a model must already be configured (run the CLI once if not).
 */
public final class WebLauncher {

    private static final Logger log = LoggerFactory.getLogger(WebLauncher.class);

    private WebLauncher() {
    }

    public static void main(String[] args) throws Exception {
        log.info("Starting Pig Agent Web Console");

        AgentBootstrap.Services s;
        try {
            s = AgentBootstrap.build(false); // no interactive onboarding in a headless web process
        } catch (IllegalStateException e) {
            log.error(e.getMessage());
            return;
        }

        PigAgentConfig.WebConfig web = s.config.getWeb();
        WebConsole console = new WebConsole(s.webContext(), web.getHost(), web.getPort());
        console.start();
        log.info("Web console: http://{}:{}  (loopback-only, single-user; Ctrl-C to stop)",
                web.getHost(), console.boundPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Web console shutting down...");
            console.stop();
            s.shutdownCommon();
        }));

        // Keep the process alive; the HTTP server runs on its own (daemon) threads.
        Thread.currentThread().join();
    }
}
