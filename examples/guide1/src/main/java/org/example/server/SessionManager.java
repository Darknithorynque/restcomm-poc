package org.example.server;

import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private static final SessionManager instance = new SessionManager();
    private ConcurrentHashMap<String, UserSession> activeSessions = new ConcurrentHashMap<>();

    public static SessionManager getInstance() {
        return instance;
    }

    private SessionManager() {}

    public UserSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public UserSession createSession(String sessionId, String userId, long initialQuota) {
        UserSession session = new UserSession(sessionId, userId, initialQuota);
        activeSessions.put(sessionId, session);
        return session;
    }

    public void terminateSession(String sessionId) {
        activeSessions.remove(sessionId);
    }

    public static class UserSession {
        private String sessionId;
        private String userId;
        private long remainingQuota;
        private long usedQuota;

        public UserSession(String sessionId, String userId, long initialQuota) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.remainingQuota = initialQuota;
            this.usedQuota = 0;
        }

        public synchronized boolean useQuota(long requested) {
            if (remainingQuota >= requested) {
                remainingQuota -= requested;
                usedQuota += requested;
                return true;
            }
            return false;
        }

        public synchronized long getRemainingQuota() {
            return remainingQuota;
        }

        public synchronized long getUsedQuota() {
            return usedQuota;
        }

        public synchronized void addQuota(long additionalQuota) {
            remainingQuota += additionalQuota;
        }
    }
}