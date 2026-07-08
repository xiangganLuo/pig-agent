package io.pigagent.cli.repl;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.session.Session;
import io.pigagent.session.SessionManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusLineTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    @Test
    void formatAssemblesModelSessionAndPerms() {
        String out = StatusLine.format("openai / gpt-4o", "work", "ask");

        assertThat(out).contains("openai / gpt-4o").contains("work").contains("ask");
    }

    @Test
    void formatFallsBackForBlankValues() {
        String out = StatusLine.format("  ", null, "");

        assertThat(out).contains("(none)").contains("ask");
    }

    @Test
    void fromManagersUsesSafeLabelAndNeverLeaksApiKey() {
        StoredModel model = StoredModel.create("anthropic", "sk-SECRET-KEY-VALUE-123456", null,
                "claude-sonnet-4-6");
        ModelManager models = mock(ModelManager.class);
        when(models.getCurrentModel()).thenReturn(Optional.of(model));

        SessionManager sessions = mock(SessionManager.class);
        Session session = mock(Session.class);
        when(session.name()).thenReturn("work");
        when(sessions.getCurrentSession()).thenReturn(Optional.of(session));

        ConfigurationManager config = mock(ConfigurationManager.class);
        when(config.getConfig()).thenReturn(new PigAgentConfig());

        String out = StatusLine.from(models, sessions, config);

        assertThat(out).contains("anthropic / claude-sonnet-4-6").contains("work").contains("ask");
        assertThat(out).doesNotContain("sk-SECRET-KEY-VALUE-123456");
    }

    @Test
    void fromManagersHandlesNoModelOrSession() {
        ModelManager models = mock(ModelManager.class);
        when(models.getCurrentModel()).thenReturn(Optional.empty());
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.getCurrentSession()).thenReturn(Optional.empty());
        ConfigurationManager config = mock(ConfigurationManager.class);
        when(config.getConfig()).thenReturn(new PigAgentConfig());

        String out = StatusLine.from(models, sessions, config);

        assertThat(out).contains("(none)");
    }
}
