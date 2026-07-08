package io.pigagent.cli.repl;

import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.session.SessionManager;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The selected/switched model must be tested then delegated to the managers: a session-scoped
 * switch binds the current session; a global switch sets the default; a failed connectivity test
 * changes nothing.
 */
class ModelSelectionTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    private static final StoredModel MODEL =
            new StoredModel("m1", "anthropic", "sk-x", null, "claude-sonnet-4-6");

    private static Terminal dumb() throws IOException {
        return TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .build();
    }

    @Test
    void sessionSwitch_bindsCurrentSession() throws IOException {
        ModelManager mm = mock(ModelManager.class);
        SessionManager sm = mock(SessionManager.class);
        when(mm.test(MODEL)).thenReturn(ModelManager.TestResult.success());

        boolean ok = ModelSelection.apply(dumb(), mm, sm, MODEL, false);

        assertThat(ok).isTrue();
        verify(sm).bindCurrentSessionModel("m1");
        verify(sm).reactivateCurrent();
        verify(mm, never()).setDefault(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void globalSwitch_setsDefaultAndClearsSessionBinding() throws IOException {
        ModelManager mm = mock(ModelManager.class);
        SessionManager sm = mock(SessionManager.class);
        when(mm.test(MODEL)).thenReturn(ModelManager.TestResult.success());

        boolean ok = ModelSelection.apply(dumb(), mm, sm, MODEL, true);

        assertThat(ok).isTrue();
        verify(mm).setDefault("m1");
        verify(sm).bindCurrentSessionModel(null);
        verify(sm).reactivateCurrent();
    }

    @Test
    void failedTest_changesNothing() throws IOException {
        ModelManager mm = mock(ModelManager.class);
        SessionManager sm = mock(SessionManager.class);
        when(mm.test(MODEL)).thenReturn(ModelManager.TestResult.failure("401 unauthorized"));

        boolean ok = ModelSelection.apply(dumb(), mm, sm, MODEL, false);

        assertThat(ok).isFalse();
        verify(sm, never()).bindCurrentSessionModel(org.mockito.ArgumentMatchers.any());
        verify(sm, never()).reactivateCurrent();
        verify(mm, never()).setDefault(org.mockito.ArgumentMatchers.anyString());
    }
}
