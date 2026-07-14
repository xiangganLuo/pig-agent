package io.pigagent.channel.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the stdin pipe channel's line dispatch, reply output, and lifecycle. */
class StdinPipeChannelTest {

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void pumpDispatchesEachNonBlankLine() {
        // Arrange
        List<String> received = new ArrayList<>();
        StdinPipeChannel channel = new StdinPipeChannel(in("a\n\nb\n"), new ByteArrayOutputStream());
        channel.bind(received::add);

        // Act: synchronous read to EOF
        channel.pump();

        // Assert: blank line skipped
        assertThat(received).containsExactly("a", "b");
    }

    @Test
    void sendMessageWritesToOutputStream() {
        // Arrange
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StdinPipeChannel channel = new StdinPipeChannel(in(""), out);

        // Act
        channel.sendMessage("reply");

        // Assert
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("reply");
    }

    @Test
    void identityAndLifecycle() {
        // Arrange: empty stream so the read thread exits immediately at EOF
        StdinPipeChannel channel = new StdinPipeChannel(in(""), new ByteArrayOutputStream());
        assertThat(channel.channelId()).isEqualTo("stdin");
        assertThat(channel.displayName()).isEqualTo("CLI Stdin Pipe");
        assertThat(channel.isRunning()).isFalse();

        // Act
        channel.start(msg -> { });

        // Assert
        assertThat(channel.isRunning()).isTrue();

        // Cleanup
        channel.stop();
        assertThat(channel.isRunning()).isFalse();
    }
}
