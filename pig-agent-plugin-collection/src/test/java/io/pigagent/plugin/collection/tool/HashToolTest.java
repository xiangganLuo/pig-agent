package io.pigagent.plugin.collection.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link HashTool}: well-known MD5 / SHA-256 test vectors. */
class HashToolTest {

    private final HashTool tool = new HashTool();

    @Test
    void md5_emptyString() {
        assertThat(tool.md5Hash("")).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    @Test
    void md5_abc() {
        assertThat(tool.md5Hash("abc")).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
    }

    @Test
    void sha256_emptyString() {
        assertThat(tool.sha256Hash(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256_abc() {
        assertThat(tool.sha256Hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void nullTreatedAsEmptyString() {
        assertThat(tool.md5Hash(null)).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }
}
