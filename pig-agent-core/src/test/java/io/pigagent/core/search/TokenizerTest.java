package io.pigagent.core.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tokenizer: latin lowercasing, CJK unigram+bigram, and cross-token overlap for Chinese queries. */
class TokenizerTest {

    @Test
    void tokenizesLatinAndDigitsLowercased() {
        assertThat(Tokenizer.tokenize("Hello World-Foo123"))
                .containsExactly("hello", "world", "foo123");
    }

    @Test
    void nullOrBlankYieldsEmpty() {
        assertThat(Tokenizer.tokenize(null)).isEmpty();
        assertThat(Tokenizer.tokenize("   \n\t")).isEmpty();
    }

    @Test
    void cjkRunEmitsUnigramsAndAdjacentBigrams() {
        // 罗湘赣 → 罗, 罗湘, 湘, 湘赣, 赣
        assertThat(Tokenizer.tokenize("罗湘赣"))
                .containsExactly("罗", "罗湘", "湘", "湘赣", "赣");
    }

    @Test
    void cjkQueryTokensOverlapDocTokens() {
        var docTokens = Tokenizer.tokenize("用户的名字是罗湘赣");
        var queryTokens = Tokenizer.tokenize("罗湘赣");
        assertThat(docTokens).containsAll(queryTokens); // so BM25 can match a Chinese name
    }
}
