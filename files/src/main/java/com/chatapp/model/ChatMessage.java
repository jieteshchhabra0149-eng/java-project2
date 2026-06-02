package com.chatapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
    String id,
    String content,
    String sender,
    String avatar,
    String color,
    MessageType type,
    String room,
    LocalDateTime timestamp,
    String threadId,
    String replyTo,
    Map<String, List<String>> reactions,
    Boolean edited,
    LocalDateTime editedAt,
    String imageUrl
) {
    public ChatMessage {
        if (reactions == null) reactions = new LinkedHashMap<>();
        if (edited == null) edited = false;
    }

    public enum MessageType {
        CHAT, JOIN, LEAVE, SYSTEM, TYPING, REACTION, EDIT
    }

    public static ChatMessage chat(String sender, String avatar, String color,
                                   String content, String room) {
        return chat(sender, avatar, color, content, room, null, null);
    }

    public static ChatMessage chat(String sender, String avatar, String color,
                                   String content, String room, String threadId, String replyTo) {
        return new ChatMessage(
            UUID.randomUUID().toString(),
            content, sender, avatar, color,
            MessageType.CHAT, room,
            LocalDateTime.now(),
            threadId, replyTo,
            new LinkedHashMap<>(), false, null, null
        );
    }

    public static ChatMessage event(String sender, String avatar, String color,
                                    MessageType type, String room) {
        String msg = type == MessageType.JOIN
            ? sender + " joined the room"
            : sender + " left the room";
        return new ChatMessage(
            UUID.randomUUID().toString(),
            msg, "System", "🔔", "#888",
            type, room,
            LocalDateTime.now(),
            null, null,
            new LinkedHashMap<>(), false, null, null
        );
    }

    public static ChatMessage typing(String sender, String room) {
        return new ChatMessage(
            UUID.randomUUID().toString(),
            sender + " is typing...", sender, "⌨️", "#888",
            MessageType.TYPING, room,
            LocalDateTime.now(),
            null, null,
            new LinkedHashMap<>(), false, null, null
        );
    }

    public static ChatMessage bot(String content, String room) {
        return new ChatMessage(
            UUID.randomUUID().toString(),
            content, "NexusGuide", "🤖", "#B06EFF",
            MessageType.CHAT, room,
            LocalDateTime.now(),
            null, null,
            new LinkedHashMap<>(), false, null, null
        );
    }

    public ChatMessage withReactions(Map<String, List<String>> newReactions) {
        return new ChatMessage(id, content, sender, avatar, color, MessageType.REACTION, room,
            timestamp, threadId, replyTo, newReactions, edited, editedAt, imageUrl);
    }

    public ChatMessage withEdit(String newContent) {
        return new ChatMessage(id, newContent, sender, avatar, color, MessageType.EDIT, room,
            timestamp, threadId, replyTo, reactions, true, LocalDateTime.now(), imageUrl);
    }
}
