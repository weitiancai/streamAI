package org.wmg.memeroyai.tool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// 手动实现对话记忆存储
public class ChatMemory {
    private static final int MAX_HISTORY = 5;
    private final Map<String, Deque<Map<String, String>>> memory = new ConcurrentHashMap<>();

    public synchronized void addHistory(String sessionId, String role, String content) {
        Deque<Map<String, String>> history = memory.computeIfAbsent(sessionId, k -> new LinkedList<>());
        history.addLast(Map.of("role", role, "content", content));
        if (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    public List<Map<String, String>> getHistory(String sessionId) {
        return new ArrayList<>(memory.getOrDefault(sessionId, new LinkedList<>()));
    }
}