package com.clitoolbox.conversation;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按用户串行、跨用户并行地执行聊天任务。
 */
public class PerUserTaskDispatcher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PerUserTaskDispatcher.class);

    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<String, UserQueue> userQueues = new ConcurrentHashMap<>();
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxPendingTasks;
    private final int maxPendingTasksPerUser;

    public PerUserTaskDispatcher(
            int threads,
            int maxPendingTasks,
            int maxPendingTasksPerUser) {
        if (threads < 1 || maxPendingTasks < 1
                || maxPendingTasksPerUser < 1
                || maxPendingTasksPerUser > maxPendingTasks) {
            throw new IllegalArgumentException("线程池参数不合法");
        }
        this.maxPendingTasks = maxPendingTasks;
        this.maxPendingTasksPerUser = maxPendingTasksPerUser;
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxPendingTasks),
                new ChatThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @return true 表示任务已进入队列；false 表示服务已关闭或队列已满。
     */
    public boolean submit(String userId, Runnable task) {
        if (closed.get() || task == null || userId == null || userId.isBlank()) {
            return false;
        }
        AtomicBoolean accepted = new AtomicBoolean();
        userQueues.compute(userId, (key, existing) -> {
            if (closed.get()) {
                return existing;
            }
            int pending = pendingTasks.incrementAndGet();
            if (pending > maxPendingTasks) {
                pendingTasks.decrementAndGet();
                return existing;
            }

            UserQueue queue = existing == null ? new UserQueue() : existing;
            int userPending = queue.pendingTasks.incrementAndGet();
            if (userPending > maxPendingTasksPerUser) {
                queue.pendingTasks.decrementAndGet();
                pendingTasks.decrementAndGet();
                return existing;
            }
            Runnable countedTask = () -> {
                try {
                    task.run();
                } finally {
                    queue.pendingTasks.decrementAndGet();
                    pendingTasks.decrementAndGet();
                }
            };
            queue.tasks.add(countedTask);
            accepted.set(true);

            if (!queue.running) {
                queue.running = true;
                try {
                    executor.execute(() -> drain(key, queue));
                } catch (RejectedExecutionException e) {
                    queue.running = false;
                    queue.tasks.remove(countedTask);
                    queue.pendingTasks.decrementAndGet();
                    pendingTasks.decrementAndGet();
                    accepted.set(false);
                }
            }
            // 已成功调度时必须保留映射；drain 可能已经取走唯一任务，
            // 但该任务尚未执行完，提前移除会破坏同用户串行保证。
            return accepted.get() ? queue : (queue.tasks.isEmpty() ? null : queue);
        });
        return accepted.get();
    }

    private void drain(String userId, UserQueue queue) {
        while (true) {
            Runnable task = queue.tasks.poll();
            if (task != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOG.warn("聊天任务执行失败", t);
                }
                continue;
            }

            AtomicBoolean removed = new AtomicBoolean();
            userQueues.compute(userId, (key, current) -> {
                if (current != queue) {
                    removed.set(true);
                    return current;
                }
                if (queue.tasks.isEmpty()) {
                    queue.running = false;
                    removed.set(true);
                    return null;
                }
                return queue;
            });
            if (removed.get()) {
                return;
            }
        }
    }

    public int pendingTaskCount() {
        return pendingTasks.get();
    }

    int activeUserQueueCount() {
        return userQueues.size();
    }

    public void close(Duration timeout) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(10));
    }

    private static final class UserQueue {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pendingTasks = new AtomicInteger();
        private boolean running;
    }

    private static final class ChatThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "deepseek-chat-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
