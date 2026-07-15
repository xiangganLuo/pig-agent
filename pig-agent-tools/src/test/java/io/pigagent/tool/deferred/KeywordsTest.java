package io.pigagent.tool.deferred;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 关键词切词：驼峰拆分、描述切词、去短词、小写。 */
class KeywordsTest {

    @Test
    void splitsCamelCaseName() {
        Set<String> kw = Keywords.from("webSearch", "");
        assertThat(kw).contains("web", "search");
    }

    @Test
    void includesDescriptionWordsLowercased() {
        Set<String> kw = Keywords.from("fetchUrl", "Fetch a URL over HTTP");
        assertThat(kw).contains("fetch", "url", "over", "http");
    }

    @Test
    void dropsTokensShorterThanTwoChars() {
        Set<String> kw = Keywords.from("a", "b of x");
        // 单字符 a/b/x 被丢弃；"of" 保留
        assertThat(kw).containsExactly("of");
    }

    @Test
    void nullAndBlankAreEmpty() {
        assertThat(Keywords.from(null, null)).isEmpty();
        assertThat(Keywords.tokenize("  ")).isEmpty();
    }

    @Test
    void tokenizeSplitsQueryOnSeparatorsAndCamelCase() {
        assertThat(Keywords.tokenize("send-email, now")).contains("send", "email", "now");
    }
}
