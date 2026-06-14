package com.chatapp.controller;

import com.chatapp.model.ChatMessage;
import com.chatapp.model.UserSession;
import com.chatapp.service.AiGuideService;
import com.chatapp.service.AuthService;
import com.chatapp.service.ChatService;
import com.chatapp.service.RateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final AiGuideService aiGuideService;
    private final AuthService authService;
    private final RateLimiterService rateLimiterService;

    public ChatController(
        ChatService chatService,
        AiGuideService aiGuideService,
        AuthService authService,
        RateLimiterService rateLimiterService
    ) {
        this.chatService = chatService;
        this.aiGuideService = aiGuideService;
        this.authService = authService;
        this.rateLimiterService = rateLimiterService;
    }

    @MessageMapping("/chat.register")
    public void registerUser(
        @Payload Map<String, String> payload,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        String sessionId = headerAccessor.getSessionId();
        String username = authService.currentUser()
            .map(u -> u.getDisplayName())
            .orElse(payload.getOrDefault("username", "User-" + sessionId.substring(0, 6)));

        String avatar = authService.currentUser().map(u -> u.getAvatar()).orElse(null);
        String color = authService.currentUser().map(u -> u.getColor()).orElse(null);

        UserSession session = chatService.registerUser(username.trim(), sessionId, avatar, color);

        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs != null) {
            attrs.put("username", session.getUsername());
            attrs.put("sessionId", sessionId);
        }

        chatService.joinRoom(sessionId, "general");
        log.info("Registered: {}", username);
    }

    @MessageMapping("/chat.send")
    public void sendMessage(
        @Payload ChatMessage message,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        String sessionId = headerAccessor.getSessionId();
        if (!rateLimiterService.tryChat(sessionId)) {
            log.warn("Chat rate-limited for session {}", sessionId);
            return;
        }

        if (chatService.getUserBySession(sessionId).isEmpty()) {
            String name = message.sender() != null && !message.sender().isBlank()
                ? message.sender() : "Guest";
            chatService.registerUser(name, sessionId, message.avatar(), message.color());
            chatService.joinRoom(sessionId, message.room() != null ? message.room() : "general");
        }

        String roomId = chatService.getUserBySession(sessionId)
            .map(UserSession::getCurrentRoom)
            .orElse(message.room());

        boolean aiCommand = aiGuideService.isAiCommand(message.content());
        boolean aiHelpRoom = chatService.isAiHelpRoom(roomId);

        ChatMessage toSend = new ChatMessage(
            message.id(),
            message.content(),
            message.sender(),
            message.avatar(),
            message.color(),
            message.type() != null ? message.type() : ChatMessage.MessageType.CHAT,
            roomId,
            message.timestamp() != null ? message.timestamp() : java.time.LocalDateTime.now(),
            message.threadId(),
            message.replyTo(),
            message.reactions(),
            message.edited(),
            message.editedAt(),
            message.imageUrl()
        );

        chatService.processMessage(sessionId, toSend);

        if (aiCommand) {
            String question = aiGuideService.extractQuestion(message.content());
            if (!question.isBlank()) {
                aiGuideService.replyInRoom(sessionId, question);
            }
        } else if (aiHelpRoom && !"NexusGuide".equals(message.sender())) {
            aiGuideService.replyInRoom(sessionId, message.content());
        }
    }

    @MessageMapping("/chat.join")
    public void joinRoom(
        @Payload Map<String, String> payload,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        String roomId = payload.get("roomId");
        String sessionId = headerAccessor.getSessionId();
        if (roomId != null && !roomId.isBlank()) {
            chatService.joinRoom(sessionId, roomId);
        }
    }

    @MessageMapping("/chat.typing")
    public void handleTyping(SimpMessageHeaderAccessor headerAccessor) {
        chatService.broadcastTyping(headerAccessor.getSessionId());
    }

    @MessageMapping("/chat.react")
    public void react(
        @Payload Map<String, String> payload,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        chatService.addReaction(
            headerAccessor.getSessionId(),
            payload.get("messageId"),
            payload.get("emoji")
        );
    }

    @MessageMapping("/chat.edit")
    public void edit(
        @Payload Map<String, String> payload,
        SimpMessageHeaderAccessor headerAccessor
    ) {
        chatService.editMessage(
            headerAccessor.getSessionId(),
            payload.get("messageId"),
            payload.get("content")
        );
    }
}
