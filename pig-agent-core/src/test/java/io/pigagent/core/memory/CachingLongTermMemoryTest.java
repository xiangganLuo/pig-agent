package io.pigagent.core.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link CachingLongTermMemory} Decorator. The counting fake reports how many
 * times the underlying store is actually read (on subscription) and written, so these assert the
 * per-turn de-duplication: same query → one disk read, new query → fresh read, {@code record}
 * delegates and invalidates, and a disabled backing tier stays a no-op through the decorator.
 */
class CachingLongTermMemoryTest {

    /** Counts underlying disk reads (subscriptions) and record writes. */
    static final class CountingMemory implements LongTermMemory {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger records = new AtomicInteger();
        volatile String content = "MEM";

        @Override
        public Mono<String> retrieve(Msg query) {
            return Mono.fromSupplier(() -> {
                reads.incrementAndGet();
                return content;
            });
        }

        @Override
        public Mono<Void> record(List<Msg> messages) {
            return Mono.<Void>fromRunnable(records::incrementAndGet);
        }
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    @Test
    void sameQuery_readsDiskOnce() {
        // Arrange
        CountingMemory backing = new CountingMemory();
        CachingLongTermMemory cache = new CachingLongTermMemory(backing);

        // Act: two retrievals with the same query text (distinct Msg objects → proves content keying).
        String first = cache.retrieve(user("Q1")).block();
        String second = cache.retrieve(user("Q1")).block();

        // Assert
        assertThat(first).isEqualTo("MEM");
        assertThat(second).isEqualTo("MEM");
        assertThat(backing.reads.get()).as("underlying read exactly once for a repeated query").isEqualTo(1);
    }

    @Test
    void newQuery_reReadsDisk() {
        // Arrange
        CountingMemory backing = new CountingMemory();
        CachingLongTermMemory cache = new CachingLongTermMemory(backing);

        // Act
        cache.retrieve(user("Q1")).block();
        cache.retrieve(user("Q2")).block();

        // Assert: a changed query (new turn) misses and re-reads.
        assertThat(backing.reads.get()).isEqualTo(2);
    }

    @Test
    void record_delegatesToUnderlying() {
        // Arrange
        CountingMemory backing = new CountingMemory();
        CachingLongTermMemory cache = new CachingLongTermMemory(backing);

        // Act
        cache.record(List.of(user("remember this"))).block();

        // Assert: write is passed straight through to the delegate.
        assertThat(backing.records.get()).isEqualTo(1);
    }

    @Test
    void record_invalidatesCache_soNextRetrieveReReads() {
        // Arrange
        CountingMemory backing = new CountingMemory();
        CachingLongTermMemory cache = new CachingLongTermMemory(backing);

        // Act: read, then record (turn end), then read the SAME query again.
        cache.retrieve(user("Q1")).block();
        cache.record(List.of(user("x"))).block();
        cache.retrieve(user("Q1")).block();

        // Assert: record invalidated the cache, so the second retrieve re-read (no stale value).
        assertThat(backing.reads.get()).isEqualTo(2);
    }

    @Test
    void disabledBackingTier_throughDecorator_isNoOp() {
        // Arrange: a disabled composite behind the decorator.
        CountingMemory backing = new CountingMemory();
        CompositeLongTermMemory composite = new CompositeLongTermMemory(backing, false);
        composite.setSessionMemory(backing);
        CachingLongTermMemory cache = new CachingLongTermMemory(composite);

        // Act
        String retrieved = cache.retrieve(user("Q1")).block();
        cache.record(List.of(user("x"))).block();

        // Assert: disabled behavior is unchanged — nothing retrieved, nothing recorded, no disk touch.
        assertThat(retrieved).isEmpty();
        assertThat(backing.reads.get()).isZero();
        assertThat(backing.records.get()).isZero();
    }
}
