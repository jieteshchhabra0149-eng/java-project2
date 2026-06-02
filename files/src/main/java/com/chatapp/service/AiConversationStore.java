package com.chatapp.service;

import com.chatapp.model.AiHistoryTurn;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiConversationStore {

    private static final int MAX_TURNS = 40;

    private final ConcurrentHashMap<String, List<AiHistoryTurn>> sessions = new ConcurrentHashMap<>();

    public List<AiHistoryTurn> getHistory(String sessionKey) {
        return new ArrayList<>(sessions.getOrDefault(sessionKey, List.of()));
    }

    public void append(String sessionKey, String role, String text) {
        sessions.compute(sessionKey, (k, list) -> {
            List<AiHistoryTurn> copy = list == null ? new ArrayList<>() : new ArrayList<>(list);
            copy.add(new AiHistoryTurn(role, text));
            if (copy.size() > MAX_TURNS) {
                copy = new ArrayList<>(copy.subList(copy.size() - MAX_TURNS, copy.size()));
            }
            return copy;
        });
    }

    public void clear(String sessionKey) {
        sessions.remove(sessionKey);
    }
}
