package com.chatapp.service;

import com.chatapp.model.AiChatResponse;
import com.chatapp.model.AiHistoryTurn;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class AiGuideService {

    private final GeminiGuideService geminiGuideService;
    private final ChatService chatService;
    private final AiConversationStore conversationStore;
    private final RateLimiterService rateLimiterService;

    public AiGuideService(
        GeminiGuideService geminiGuideService,
        ChatService chatService,
        AiConversationStore conversationStore,
        RateLimiterService rateLimiterService
    ) {
        this.geminiGuideService = geminiGuideService;
        this.chatService = chatService;
        this.conversationStore = conversationStore;
        this.rateLimiterService = rateLimiterService;
    }

    public AiChatResponse chat(String sessionKey, String message, List<AiHistoryTurn> clientHistory, String topic) {
        if (!rateLimiterService.tryAi(sessionKey)) {
            return AiChatResponse.fail("AI rate limit reached. Please wait a minute.");
        }
        List<AiHistoryTurn> history = mergeHistory(sessionKey, clientHistory);
        String reply = geminiGuideService.ask(message, history, topic);
        if (!reply.startsWith("NexusGuide error") && !reply.startsWith("AI guide is not configured")) {
            conversationStore.append(sessionKey, "user", message);
            conversationStore.append(sessionKey, "assistant", reply);
        }
        if (reply.startsWith("NexusGuide error") || reply.startsWith("AI guide is not configured")) {
            return AiChatResponse.fail(reply);
        }
        return AiChatResponse.ok(reply);
    }

    public void streamChat(
        String sessionKey,
        String message,
        List<AiHistoryTurn> clientHistory,
        String topic,
        SseEmitter emitter
    ) {
        if (!rateLimiterService.tryAi(sessionKey)) {
            sendStreamError(emitter, "AI rate limit reached. Please wait a minute.");
            return;
        }
        List<AiHistoryTurn> history = mergeHistory(sessionKey, clientHistory);
        StringBuilder full = new StringBuilder();
        Consumer<String> onChunk = chunk -> {
            full.append(chunk);
            try {
                emitter.send(SseEmitter.event().name("chunk").data(chunk));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        };
        geminiGuideService.streamAsk(message, history, topic, onChunk, () -> {
            try {
                conversationStore.append(sessionKey, "user", message);
                conversationStore.append(sessionKey, "assistant", full.toString());
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, err -> sendStreamError(emitter, err));
    }

    public boolean isAiCommand(String content) {
        if (content == null) return false;
        String t = content.trim().toLowerCase();
        return t.startsWith("/ai ") || t.startsWith("/guide ") || t.startsWith("@guide ");
    }

    public String extractQuestion(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.toLowerCase().startsWith("/ai ")) return trimmed.substring(4).trim();
        if (trimmed.toLowerCase().startsWith("/guide ")) return trimmed.substring(7).trim();
        if (trimmed.toLowerCase().startsWith("@guide ")) return trimmed.substring(7).trim();
        return trimmed;
    }

    @Async("virtualThreadExecutor")
    public void replyInRoom(String sessionId, String question) {
        Optional<String> roomId = chatService.getUserBySession(sessionId).map(u -> u.getCurrentRoom());
        if (roomId.isEmpty() || question.isBlank()) return;
        if (!rateLimiterService.tryAi(sessionId)) return;

        chatService.broadcastAiThinking(roomId.get());
        String topic = "chat room #" + roomId.get();
        String answer = geminiGuideService.ask(question, List.of(), topic);
        chatService.postBotMessage(roomId.get(), answer);
    }

    private List<AiHistoryTurn> mergeHistory(String sessionKey, List<AiHistoryTurn> clientHistory) {
        if (clientHistory != null && !clientHistory.isEmpty()) {
            return clientHistory;
        }
        return conversationStore.getHistory(sessionKey);
    }

    private void sendStreamError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
