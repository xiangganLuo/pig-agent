package io.pigagent.onboarding;

import io.pigagent.model.ModelKind;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offline coverage for the onboarding embedding-model persistence decision (capability
 * {@code embedding-model-layer}): on a passing {@code /embeddings} test the model is saved and becomes
 * the default embedding model when none is set; a failed test saves nothing. The interactive gathering
 * and the live round-trip are out of scope here ({@code /ls:itest} / manual).
 */
class OnboardingWizardTest {

    private static StoredModel embedding() {
        return StoredModel.create("openai", "k", "https://api.x/v1", "text-embedding-3-small",
                ModelKind.EMBEDDING);
    }

    @Test
    void successWithNoDefault_savesAndSetsDefaultEmbedding() {
        ModelManager mm = mock(ModelManager.class);
        StoredModel emb = embedding();
        when(mm.test(emb)).thenReturn(ModelManager.TestResult.success());
        when(mm.getDefaultEmbeddingModelId()).thenReturn(null);

        ModelManager.TestResult r = OnboardingWizard.tryAddEmbeddingModel(mm, emb);

        assertThat(r.ok()).isTrue();
        verify(mm).add(emb);
        verify(mm).setDefaultEmbeddingModelId(emb.id());
    }

    @Test
    void successWithExistingDefault_savesButDoesNotOverrideDefault() {
        ModelManager mm = mock(ModelManager.class);
        StoredModel emb = embedding();
        when(mm.test(emb)).thenReturn(ModelManager.TestResult.success());
        when(mm.getDefaultEmbeddingModelId()).thenReturn("already-set");

        OnboardingWizard.tryAddEmbeddingModel(mm, emb);

        verify(mm).add(emb);
        verify(mm, never()).setDefaultEmbeddingModelId(anyString());
    }

    @Test
    void failedTest_savesNothing() {
        ModelManager mm = mock(ModelManager.class);
        StoredModel emb = embedding();
        when(mm.test(emb)).thenReturn(ModelManager.TestResult.failure("connection refused"));

        ModelManager.TestResult r = OnboardingWizard.tryAddEmbeddingModel(mm, emb);

        assertThat(r.ok()).isFalse();
        verify(mm, never()).add(any());
        verify(mm, never()).setDefaultEmbeddingModelId(anyString());
    }
}
