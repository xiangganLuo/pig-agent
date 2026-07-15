package io.pigagent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the tools-core-slim decision: {@code pig-agent-tools} no longer declares Apache Lucene (the
 * dead {@code ToolDiscovery} that used it was removed), so Lucene classes must not be on this module's
 * classpath. Fails if the heavy dependency ever creeps back in.
 */
class LuceneRemovedTest {

    @Test
    void luceneIsNotOnTheToolsClasspath() {
        assertThatThrownBy(() -> Class.forName("org.apache.lucene.store.ByteBuffersDirectory"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.apache.lucene.queryparser.classic.QueryParser"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void toolDiscoveryClassIsGone() {
        assertThatThrownBy(() -> Class.forName("io.pigagent.tool.discovery.ToolDiscovery"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void sanityClasspathHasToolsItself() {
        // Control: the module's own classes ARE loadable (so the negatives above are meaningful).
        assertThat(io.pigagent.tool.shell.ShellTools.class).isNotNull();
    }
}
