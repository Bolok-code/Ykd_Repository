package ykd.ykd.processor;

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
 * 按用户串行、跨用户并行地执行任务。
 */
public class PerUserTaskDispatcher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PerUserTaskDispatcher.class);

    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<String, UserQueue> userQueues = new ConcurrentHashMap<>();
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxPendingTasks;
    private final int maxPendingTasksPerUser;

    public PerUserTaskDispatcher(int threads, int maxPendingTasks, int maxPendingTasksPerUser) {
        if (threads < 1 || maxPendingTasks < 1
                || maxPendingTasksPerUser < 1
                || maxPendingTasksPerUser > maxPendingTasks) {
            throw new IllegalArgumentException("线程池参数不合法");
        }
        this.maxPendingTasks = maxPendingTasks;
        this.maxPendingTasksPerUser = maxPendingTasksPerUser;
        this.executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxPendingTasks),
                new BotThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

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
                    LOG.warn("任务执行失败: userId={}", userId, t);
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

    private static final class BotThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ykd-bot-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
