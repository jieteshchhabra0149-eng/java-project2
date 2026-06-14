package com.chatapp.service;

import com.chatapp.entity.StoredMessage;
import com.chatapp.model.ChatMessage;
import com.chatapp.repository.StoredMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MessagePersistenceService {

    private final StoredMessageRepository repository;
    private final ObjectMapper objectMapper;

    public MessagePersistenceService(StoredMessageRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void save(ChatMessage message) {
        if (message.type() == ChatMessage.MessageType.TYPING) return;
        try {
            saveInternal(message);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(MessagePersistenceService.class)
                .warn("Failed to persist message {}: {}", message.id(), e.getMessage());
        }
    }

    private void saveInternal(ChatMessage message) {
        StoredMessage entity = repository.findByMessageId(message.id()).orElse(new StoredMessage());
        entity.setMessageId(message.id());
        entity.setRoomId(message.room());
        entity.setContent(message.content());
        entity.setSender(message.sender());
        entity.setAvatar(message.avatar());
        entity.setColor(message.color());
        entity.setType(message.type().name());
        entity.setCreatedAt(message.timestamp());
        entity.setThreadId(message.threadId());
        entity.setReplyTo(message.replyTo());
        entity.setEdited(Boolean.TRUE.equals(message.edited()));
        entity.setEditedAt(message.editedAt());
        try {
            entity.setReactionsJson(objectMapper.writeValueAsString(
                message.reactions() != null ? message.reactions() : Map.of()
            ));
        } catch (Exception e) {
            entity.setReactionsJson("{}");
        }
        entity.setImageUrl(message.imageUrl());
        repository.save(entity);
    }

    public List<ChatMessage> loadRoomHistory(String roomId) {
        return repository.findTop100ByRoomIdOrderByCreatedAtAsc(roomId).stream()
            .map(this::toChatMessage)
            .toList();
    }

    public Optional<ChatMessage> findById(String messageId) {
        return repository.findByMessageId(messageId).map(this::toChatMessage);
    }

    private ChatMessage toChatMessage(StoredMessage e) {
        Map<String, List<String>> reactions = parseReactions(e.getReactionsJson());
        return new ChatMessage(
            e.getMessageId(),
            e.getContent(),
            e.getSender(),
            e.getAvatar(),
            e.getColor(),
            ChatMessage.MessageType.valueOf(e.getType()),
            e.getRoomId(),
            e.getCreatedAt(),
            e.getThreadId(),
            e.getReplyTo(),
            reactions,
            e.isEdited(),
            e.getEditedAt(),
            e.getImageUrl()
        );
    }

    private Map<String, List<String>> parseReactions(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
