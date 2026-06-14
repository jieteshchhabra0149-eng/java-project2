package com.chatapp.service;

import com.chatapp.model.ChatMessage;
import com.chatapp.model.ChatRoom;
import com.chatapp.model.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    public static final String AI_HELP_ROOM = "ai-help";

    private final SimpMessagingTemplate messagingTemplate;
    private final MessagePersistenceService persistence;
    private final ConcurrentHashMap<String, UserSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatRoom> chatRooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>> messageHistory = new ConcurrentHashMap<>();
    private final AtomicInteger userCounter = new AtomicInteger(0);
    private final ExecutorService broadcastExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(SimpMessagingTemplate messagingTemplate, MessagePersistenceService persistence) {
        this.messagingTemplate = messagingTemplate;
        this.persistence = persistence;
        initializeDefaultRooms();
    }

    private void initializeDefaultRooms() {
        createRoom("general", "General", "The main hangout for everyone", "💬");
        createRoom("tech", "Tech Talk", "Programming, tools, and all things tech", "💻");
        createRoom("random", "Random", "Off-topic and fun conversations", "🎲");
        createRoom("announcements", "Announcements", "Important updates and news", "📢");
        createRoom(AI_HELP_ROOM, "AI Help", "Ask NexusGuide anything — every message is answered by AI", "🤖");
        messageHistory.forEach((roomId, list) -> {
            List<ChatMessage> fromDb = persistence.loadRoomHistory(roomId);
            if (!fromDb.isEmpty()) {
                list.clear();
                list.addAll(fromDb);
            }
        });
    }

    public ChatRoom createRoom(String id, String name, String description, String icon) {
        ChatRoom room = new ChatRoom(id, name, description, icon);
        chatRooms.put(id, room);
        CopyOnWriteArrayList<ChatMessage> history = new CopyOnWriteArrayList<>(persistence.loadRoomHistory(id));
        messageHistory.put(id, history);
        log.info("Created room: {} ({})", name, id);
        return room;
    }

    public UserSession registerUser(String username, String sessionId, String avatar, String color) {
        int index = userCounter.getAndIncrement();
        UserSession session = new UserSession(username, sessionId, index);
        if (avatar != null) session.setAvatar(avatar);
        if (color != null) session.setColor(color);

        UserSession existing = activeSessions.putIfAbsent(sessionId, session);
        if (existing != null) return existing;

        log.info("User registered: {} [{}]", username, sessionId);
        return session;
    }

    public Optional<UserSession> removeUser(String sessionId) {
        UserSession session = activeSessions.remove(sessionId);
        if (session != null) {
            ChatRoom room = chatRooms.get(session.getCurrentRoom());
            if (room != null) room.removeUser(session.getUsername());
            session.setOnline(false);
        }
        return Optional.ofNullable(session);
    }

    @Async("virtualThreadExecutor")
    public void joinRoom(String sessionId, String roomId) {
        UserSession user = activeSessions.get(sessionId);
        ChatRoom room = chatRooms.get(roomId);
        if (user == null || room == null) return;

        String prevRoom = user.getCurrentRoom();
        if (prevRoom != null && !prevRoom.equals(roomId)) {
            ChatRoom prev = chatRooms.get(prevRoom);
            if (prev != null) prev.removeUser(user.getUsername());
        }

        user.setCurrentRoom(roomId);
        room.addUser(user.getUsername());

        ChatMessage joinMsg = ChatMessage.event(
            user.getUsername(), user.getAvatar(), user.getColor(),
            ChatMessage.MessageType.JOIN, roomId
        );
        storeAndBroadcast(roomId, joinMsg, false);
        sendHistoryToUser(sessionId, roomId);
    }

    @Async("virtualThreadExecutor")
    public void processMessage(String sessionId, ChatMessage message) {
        UserSession user = activeSessions.get(sessionId);
        if (user == null) return;

        user.updateLastSeen();
        String roomId = user.getCurrentRoom();
        ChatRoom room = chatRooms.get(roomId);
        if (room == null) return;

        room.incrementMessages();
        storeAndBroadcast(roomId, message, true);
    }

    @Async("virtualThreadExecutor")
    public void addReaction(String sessionId, String messageId, String emoji) {
        UserSession user = activeSessions.get(sessionId);
        if (user == null || emoji == null || emoji.isBlank()) return;

        String roomId = user.getCurrentRoom();
        CopyOnWriteArrayList<ChatMessage> history = messageHistory.get(roomId);
        if (history == null) return;

        for (int i = 0; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            if (!msg.id().equals(messageId)) continue;

            Map<String, List<String>> reactions = new LinkedHashMap<>(msg.reactions());
            List<String> users = new ArrayList<>(reactions.getOrDefault(emoji, List.of()));
            if (!users.contains(user.getUsername())) {
                users.add(user.getUsername());
            }
            reactions.put(emoji, users);
            ChatMessage updated = msg.withReactions(reactions);
            history.set(i, updated);
            persistence.save(updated);
            broadcastExecutor.submit(() -> broadcastToRoom(roomId, updated));
            break;
        }
    }

    @Async("virtualThreadExecutor")
    public void editMessage(String sessionId, String messageId, String newContent) {
        UserSession user = activeSessions.get(sessionId);
        if (user == null || newContent == null || newContent.isBlank()) return;

        String roomId = user.getCurrentRoom();
        CopyOnWriteArrayList<ChatMessage> history = messageHistory.get(roomId);
        if (history == null) return;

        for (int i = 0; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            if (!msg.id().equals(messageId)) continue;
            if (!msg.sender().equals(user.getUsername())) return;

            ChatMessage updated = msg.withEdit(newContent.trim());
            history.set(i, updated);
            persistence.save(updated);
            broadcastExecutor.submit(() -> broadcastToRoom(roomId, updated));
            break;
        }
    }

    @Async("virtualThreadExecutor")
    public void broadcastTyping(String sessionId) {
        UserSession user = activeSessions.get(sessionId);
        if (user == null) return;
        ChatMessage typingMsg = ChatMessage.typing(user.getUsername(), user.getCurrentRoom());
        messagingTemplate.convertAndSend("/topic/room/" + user.getCurrentRoom() + "/typing", typingMsg);
    }

    public void broadcastAiThinking(String roomId) {
        ChatMessage thinking = new ChatMessage(
            UUID.randomUUID().toString(),
            "NexusGuide is thinking…",
            "NexusGuide", "🤖", "#B06EFF",
            ChatMessage.MessageType.TYPING, roomId,
            java.time.LocalDateTime.now(),
            null, null, Map.of(), false, null, null
        );
        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/typing", thinking);
    }

    public void postBotMessage(String roomId, String content) {
        ChatRoom room = chatRooms.get(roomId);
        if (room == null || content == null || content.isBlank()) return;
        ChatMessage botMsg = ChatMessage.bot(content, roomId);
        room.incrementMessages();
        storeAndBroadcast(roomId, botMsg, true);
    }

    private void storeAndBroadcast(String roomId, ChatMessage message, boolean persist) {
        CopyOnWriteArrayList<ChatMessage> history = messageHistory.get(roomId);
        if (history != null && message.type() != ChatMessage.MessageType.TYPING) {
            if (message.type() == ChatMessage.MessageType.EDIT || message.type() == ChatMessage.MessageType.REACTION) {
                // already updated in place for edit/reaction
            } else {
                history.add(message);
                while (history.size() > 100) history.remove(0);
            }
        }
        if (persist && message.type() != ChatMessage.MessageType.TYPING) {
            persistence.save(message);
        }
        broadcastExecutor.submit(() -> broadcastToRoom(roomId, message));
    }

    private void broadcastToRoom(String roomId, ChatMessage message) {
        messagingTemplate.convertAndSend("/topic/room/" + roomId, message);
    }

    private void sendHistoryToUser(String sessionId, String roomId) {
        List<ChatMessage> history = messageHistory.getOrDefault(roomId, new CopyOnWriteArrayList<>());
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/history", history);
    }

    public Collection<ChatRoom> getAllRooms() { return chatRooms.values(); }
    public Optional<ChatRoom> getRoom(String roomId) { return Optional.ofNullable(chatRooms.get(roomId)); }
    public Collection<UserSession> getActiveUsers() { return activeSessions.values(); }
    public int getOnlineUserCount() { return activeSessions.size(); }
    public List<ChatMessage> getRoomHistory(String roomId) {
        return messageHistory.getOrDefault(roomId, new CopyOnWriteArrayList<>());
    }
    public Optional<UserSession> getUserBySession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }
    public boolean isAiHelpRoom(String roomId) {
        return AI_HELP_ROOM.equals(roomId);
    }
}
