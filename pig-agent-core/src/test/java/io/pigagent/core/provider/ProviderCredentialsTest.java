package io.pigagent.core.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderCredentialsTest {

    @Test
    void putAndGetReturnsValue() {
        ProviderCredentials creds = new ProviderCredentials().put("KEY", "value");
        assertThat(creds.get("KEY")).contains("value");
        assertThat(creds.has("KEY")).isTrue();
    }

    @Test
    void putReturnsNewInstance() {
        ProviderCredentials original = new ProviderCredentials();
        ProviderCredentials updated = original.put("KEY", "value");
        assertThat(original.has("KEY")).isFalse();
        assertThat(updated.has("KEY")).isTrue();
    }

    @Test
    void getRequiredThrowsOnMissing() {
        ProviderCredentials creds = new ProviderCredentials();
        assertThatThrownBy(() -> creds.getRequired("MISSING"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
