package com.chatapp.model;

import java.util.ArrayList;
import java.util.List;

public class AiChatRequest {

    private String message;
    private String topic;
    private List<AiHistoryTurn> history = new ArrayList<>();

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public List<AiHistoryTurn> getHistory() {
        return history;
    }

    public void setHistory(List<AiHistoryTurn> history) {
        this.history = history != null ? history : new ArrayList<>();
    }
}
