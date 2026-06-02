package com.investory.crawler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSessionManagerTest {

    @Test
    void finishSessionKeepsReplayEventsForLateStreamSubscribers() {
        AiSessionManager manager = new AiSessionManager();
        long userId = 42L;

        manager.startSession(userId);
        manager.emitDone(userId);
        manager.finishSession(userId);

        assertFalse(manager.isActive(userId));
        assertTrue(manager.hasReplayEvents(userId));
    }

    @Test
    void clearSessionRemovesReplayEvents() {
        AiSessionManager manager = new AiSessionManager();
        long userId = 42L;

        manager.startSession(userId);
        manager.emitError(userId, "failed");
        manager.clearSession(userId);

        assertFalse(manager.isActive(userId));
        assertFalse(manager.hasReplayEvents(userId));
    }
}
