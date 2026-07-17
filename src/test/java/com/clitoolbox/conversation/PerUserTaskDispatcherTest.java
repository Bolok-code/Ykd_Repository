package com.clitoolbox.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PerUserTaskDispatcherTest {

    @Test
    void preservesSubmissionOrderForSameUser() throws Exception {
        PerUserTaskDispatcher dispatcher = new PerUserTaskDispatcher(4, 10, 5);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstMayFinish = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(3);

        try {
            assertTrue(dispatcher.submit("user-a", () -> {
                order.add(1);
                await(firstMayFinish);
                allFinished.countDown();
            }));
            assertTrue(dispatcher.submit("user-a", () -> {
                order.add(2);
                allFinished.countDown();
            }));
            assertTrue(dispatcher.submit("user-a", () -> {
                order.add(3);
                allFinished.countDown();
            }));

            firstMayFinish.countDown();
            assertTrue(allFinished.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3), order);
        } finally {
            firstMayFinish.countDown();
            dispatcher.close(Duration.ofSeconds(3));
        }
    }

    @Test
    void runsDifferentUsersConcurrently() throws Exception {
        PerUserTaskDispatcher dispatcher = new PerUserTaskDispatcher(2, 10, 5);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bothFinished = new CountDownLatch(2);

        Runnable task = () -> {
            bothStarted.countDown();
            await(release);
            bothFinished.countDown();
        };

        try {
            assertTrue(dispatcher.submit("user-a", task));
            assertTrue(dispatcher.submit("user-b", task));
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(bothFinished.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            dispatcher.close(Duration.ofSeconds(3));
        }
    }

    @Test
    void neverOverlapsTasksForSameUser() throws Exception {
        PerUserTaskDispatcher dispatcher = new PerUserTaskDispatcher(2, 10, 5);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try {
            assertTrue(dispatcher.submit("user-a", () -> {
                firstStarted.countDown();
                await(releaseFirst);
            }));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            assertTrue(dispatcher.submit("user-a", secondStarted::countDown));
            assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            dispatcher.close(Duration.ofSeconds(3));
        }
    }

    @Test
    void rejectsTasksBeyondGlobalPendingLimit() {
        PerUserTaskDispatcher dispatcher = new PerUserTaskDispatcher(1, 2, 2);
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertTrue(dispatcher.submit("user-a", () -> await(release)));
            assertTrue(dispatcher.submit("user-a", () -> await(release)));
            assertFalse(dispatcher.submit("user-b", () -> {
            }));
            assertEquals(2, dispatcher.pendingTaskCount());
        } finally {
            release.countDown();
            dispatcher.close(Duration.ofSeconds(3));
        }
    }

    @Test
    void limitsPendingTasksPerUserAndCleansFinishedQueue() throws Exception {
        PerUserTaskDispatcher dispatcher = new PerUserTaskDispatcher(2, 10, 2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);

        try {
            assertTrue(dispatcher.submit("user-a", () -> {
                await(release);
                finished.countDown();
            }));
            assertTrue(dispatcher.submit("user-a", finished::countDown));
            assertFalse(dispatcher.submit("user-a", () -> {
            }));

            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertTrue(waitUntilQueuesAreClean(dispatcher));
            assertEquals(0, dispatcher.pendingTaskCount());
        } finally {
            release.countDown();
            dispatcher.close(Duration.ofSeconds(3));
        }
    }

    @Test
    void continuesAfterTaskFailureAndRejectsAfterClose() throws Exception {
        PerUserTaskDispatcher dispatcher = new PerUserTaskDispatcher(1, 10, 5);
        CountDownLatch secondFinished = new CountDownLatch(1);

        assertTrue(dispatcher.submit("user-a", () -> {
            throw new IllegalStateException("模拟失败");
        }));
        assertTrue(dispatcher.submit("user-a", secondFinished::countDown));
        assertTrue(secondFinished.await(2, TimeUnit.SECONDS));

        dispatcher.close(Duration.ofSeconds(3));
        assertFalse(dispatcher.submit("user-a", () -> {
        }));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitUntilQueuesAreClean(PerUserTaskDispatcher dispatcher)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (dispatcher.activeUserQueueCount() == 0) {
                return true;
            }
            Thread.sleep(10L);
        }
        return dispatcher.activeUserQueueCount() == 0;
    }
}
