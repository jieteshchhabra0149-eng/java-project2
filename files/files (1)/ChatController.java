package com.chatapp.controller;

import com.chatapp.model.ChatMessage;
import com.chatapp.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.messaging.handler.annotation.MessageMapping;

import java.util.Map;

/**
 * STOMP WebSocket controller — v2.
 *
 * New handlers:
 *  /app/chat.react   → toggle emoji reaction on a message
 *  /app/chat.edit    → edit own message
 *  /app/chat.delete  → delete own message
 *  /app/chat.dm      → send private direct message
 *  /app/chat.status  → update user status (ONLINE / AWAY / BUSY)
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @MessageMapping("/chat.register")
    public void registerUser(@Payload Map<String, String> payload,
                             SimpMessageHeaderAccessor ha) {
        String username = payload.getOrDefault("username", "").trim();
        String sessionId = ha.getSessionId();
        if (username.isBlank()) username = "User-" + sessionId.substring(0, 6);

        var session = chatService.registerUser(username, sessionId);

        Map<String, Object> attrs = ha.getSessionAttributes();
        if (attrs != null) {
            attrs.put("username", session.getUsername());
            attrs.put("sessionId", sessionId);
        }

        chatService.joinRoom(sessionId, "general");
        log.info("Registered: {} [{}]", username, sessionId);
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload ChatMessage message, SimpMessageHeaderAccessor ha) {
        chatService.processMessage(ha.getSessionId(), message);
    }

    @MessageMapping("/chat.join")
    public void joinRoom(@Payload Map<String, String> payload, SimpMessageHeaderAccessor ha) {
        String roomId = payload.get("roomId");
        if (roomId != null && !roomId.isBlank()) {
            chatService.joinRoom(ha.getSessionId(), roomId);
        }
    }

    @MessageMapping("/chat.typing")
    public void handleTyping(SimpMessageHeaderAccessor ha) {
        chatService.broadcastTyping(ha.getSessionId());
    }

    /** Toggle an emoji reaction on a message. Payload: {messageId, emoji} */
    @MessageMapping("/chat.react")
    public void reactToMessage(@Payload Map<String, String> payload, SimpMessageHeaderAccessor ha) {
        String messageId = payload.get("messageId");
        String emoji     = payload.get("emoji");
        if (messageId != null && emoji != null) {
            chatService.toggleReaction(ha.getSessionId(), messageId, emoji);
        }
    }

    /** Edit own message. Payload: {messageId, content} */
    @MessageMapping("/chat.edit")
    public void editMessage(@Payload Map<String, String> payload, SimpMessageHeaderAccessor ha) {
        String messageId = payload.get("messageId");
        String content   = payload.get("content");
        if (messageId != null && content != null) {
            chatService.editMessage(ha.getSessionId(), messageId, content);
        }
    }

    /** Delete own message. Payload: {messageId} */
    @MessageMapping("/chat.delete")
    public void deleteMessage(@Payload Map<String, String> payload, SimpMessageHeaderAccessor ha) {
        String messageId = payload.get("messageId");
        if (messageId != null) {
            chatService.deleteMessage(ha.getSessionId(), messageId);
        }
    }

    /** Send a direct message. Payload: {targetUsername, content} */
    @MessageMapping("/chat.dm")
    public void sendDM(@Payload Map<String, String> payload, SimpMessageHeaderAccessor ha) {
        String target  = payload.get("targetUsername");
        String content = payload.get("content");
        if (target != null && content != null) {
            chatService.sendDM(ha.getSessionId(), target, content);
        }
    }

    /** Update user status. Payload: {status} = ONLINE | AWAY | BUSY */
    @MessageMapping("/chat.status")
    public void updateStatus(@Payload Map<String, String> payload, SimpMessageHeaderAccessor ha) {
        String status = payload.get("status");
        if (status != null) {
            chatService.updateStatus(ha.getSessionId(), status);
        }
    }
}
