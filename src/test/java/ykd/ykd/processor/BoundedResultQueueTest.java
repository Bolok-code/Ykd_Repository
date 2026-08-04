package ykd.ykd.processor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BoundedResultQueue 行为测试：超出容量丢弃最旧、拒绝 null。
 */
class BoundedResultQueueTest {

    @Test
    void shouldDropOldestWhenOverCapacity() {
        BoundedResultQueue queue = new BoundedResultQueue(3);
        queue.add(ProcessResult.text("1", "u"));
        queue.add(ProcessResult.text("2", "u"));
        queue.add(ProcessResult.text("3", "u"));
        queue.add(ProcessResult.text("4", "u")); // 超容量，丢弃最旧的 "1"

        assertThat(queue).hasSize(3);
        assertThat(queue.poll().text()).isEqualTo("2");
        assertThat(queue.poll().text()).isEqualTo("3");
        assertThat(queue.poll().text()).isEqualTo("4");
        assertThat(queue).isEmpty();
    }

    @Test
    void shouldKeepAllUnderCapacity() {
        BoundedResultQueue queue = new BoundedResultQueue(5);
        for (int i = 1; i <= 5; i++) {
            queue.add(ProcessResult.text("msg-" + i, "u"));
        }
        assertThat(queue).hasSize(5);
        assertThat(queue.peek().text()).isEqualTo("msg-1");
    }

    @Test
    void shouldRejectNull() {
        BoundedResultQueue queue = new BoundedResultQueue(3);
        assertThat(queue.add(null)).isFalse();
        assertThat(queue).isEmpty();
    }
}
