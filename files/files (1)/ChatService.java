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
import java.util.stream.Collectors;

/**
 * Core chat service — fully thread-safe.
 *
 * Improvements in v2:
 * - Rate-limiting integration (RateLimiterService)
 * - Reaction toggle + broadcast
 * - Message edit + delete
 * - Direct messages (DM)
 * - @mention detection + targeted notification
 * - Full-text message search
 * - Unread badge tracking per user/room
 * - Username-to-session reverse lookup
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_HISTORY_PER_ROOM = 200;

    private final SimpMessagingTemplate messagingTemplate;
    private final RateLimiterService rateLimiter;

    // ── Thread-safe state ──────────────────────────────────────────────────────
    private final ConcurrentHashMap<String, UserSession> sessionById     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>      sessionByUser   = new ConcurrentHashMap<>(); // username→sessionId
    private final ConcurrentHashMap<String, ChatRoom>    chatRooms       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ChatMessage>> history = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatMessage> messageIndex    = new ConcurrentHashMap<>(); // msgId→msg
    private final AtomicInteger                          userCounter     = new AtomicInteger(0);

    private final ExecutorService broadcastExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(SimpMessagingTemplate messagingTemplate, RateLimiterService rateLimiter) {
        this.messagingTemplate = messagingTemplate;
        this.rateLimiter = rateLimiter;
        initDefaultRooms();
    }

    // ── Room Setup ─────────────────────────────────────────────────────────────

    private void initDefaultRooms() {
        createRoom("general",       "General",       "The main hangout for everyone",         "💬");
        createRoom("tech",          "Tech Talk",     "Programming, tools, and all things tech","💻");
        createRoom("random",        "Random",        "Off-topic and fun conversations",        "🎲");
        createRoom("announcements", "Announcements", "Important updates and news",             "📢");
    }

    public ChatRoom createRoom(String id, String name, String description, String icon) {
        ChatRoom room = new ChatRoom(id, name, description, icon);
        chatRooms.put(id, room);
        history.put(id, new CopyOnWriteArrayList<>());
        log.info("Room created: {} ({})", name, id);
        // Notify all clients of new room
        broadcastExecutor.submit(() ->
            messagingTemplate.convertAndSend("/topic/rooms", getAllRoomsPayload())
        );
        return room;
    }

    // ── User Lifecycle ─────────────────────────────────────────────────────────

    public UserSession registerUser(String username, String sessionId) {
        int idx = userCounter.getAndIncrement();
        UserSession session = new UserSession(username, sessionId, idx);

        UserSession prev = sessionById.putIfAbsent(sessionId, session);
        if (prev != null) return prev;

        sessionByUser.put(username.toLowerCase(), sessionId);
        log.info("Registered: {} [{}]", username, sessionId);
        broadcastUserList();
        return session;
    }

    public Optional<UserSession> removeUser(String sessionId) {
        UserSession s = sessionById.remove(sessionId);
        if (s != null) {
            sessionByUser.remove(s.getUsername().toLowerCase());
            rateLimiter.removeBucket(sessionId);
            ChatRoom room = chatRooms.get(s.getCurrentRoom());
            if (room != null) room.removeUser(s.getUsername());
            s.setOnline(false);
            broadcastUserList();
            log.info("Disconnected: {}", s.getUsername());
        }
        return Optional.ofNullable(s);
    }

    // ── Join Room ─────────────────────────────────────────────────────────────

    @Async("virtualThreadExecutor")
    public void joinRoom(String sessionId, String roomId) {
        UserSession user = sessionById.get(sessionId);
        ChatRoom room = chatRooms.get(roomId);
        if (user == null || room == null) return;

        String prev = user.getCurrentRoom();
        if (prev != null && !prev.equals(roomId)) {
            ChatRoom prevRoom = chatRooms.get(prev);
            if (prevRoom != null) prevRoom.removeUser(user.getUsername());
        }

        user.setCurrentRoom(roomId);
        user.clearUnread(roomId);
        room.addUser(user.getUsername());

        broadcastToRoom(roomId, ChatMessage.event(
            user.getUsername(), user.getAvatar(), user.getColor(),
            ChatMessage.MessageType.JOIN, roomId
        ));

        sendHistoryToUser(sessionId, roomId);
        broadcastUserList();
        log.debug("{} joined {} [{}]", user.getUsername(), roomId, Thread.currentThread().getName());
    }

    // ── Send Message ──────────────────────────────────────────────────────────

    @Async("virtualThreadExecutor")
    public void processMessage(String sessionId, ChatMessage inbound) {
        UserSession user = sessionById.get(sessionId);
        if (user == null) return;

        // Rate-limit check
        if (!rateLimiter.tryConsume(sessionId)) {
            sendErrorToUser(sessionId, "⚠️ Slow down! You're sending messages too fast.");
            return;
        }

        user.updateLastSeen();
        String roomId = user.getCurrentRoom();
        ChatRoom room = chatRooms.get(roomId);
        if (room == null) return;

        // Sanitize content
        String content = sanitize(inbound.getContent());
        if (content.isBlank()) return;

        // Build canonical message (server-side, trusted fields from session)
        ChatMessage msg = new ChatMessage(
            inbound.getId() != null ? inbound.getId() : UUID.randomUUID().toString(),
            content,
            user.getUsername(), user.getAvatar(), user.getColor(),
            ChatMessage.MessageType.CHAT, roomId,
            java.time.LocalDateTime.now(),
            inbound.getReplyToId(), null, null
        );

        // Populate reply context if present
        if (inbound.getReplyToId() != null) {
            ChatMessage original = messageIndex.get(inbound.getReplyToId());
            if (original != null) {
                msg.setReplyToSender(original.getSender());
                msg.setReplyToContent(truncate(original.getContent(), 80));
            }
        }

        // Index the message
        messageIndex.put(msg.getId(), msg);
        room.incrementMessages();

        // History
        CopyOnWriteArrayList<ChatMessage> roomHistory = history.get(roomId);
        if (roomHistory != null) {
            roomHistory.add(msg);
            if (roomHistory.size() > MAX_HISTORY_PER_ROOM) roomHistory.remove(0);
        }

        // Detect @mentions and notify mentioned users
        detectAndNotifyMentions(content, msg, roomId, user.getUsername());

        // Increment unread for users NOT in this room
        sessionById.values().forEach(u -> {
            if (!u.getUsername().equals(user.getUsername())
                    && !roomId.equals(u.getCurrentRoom())) {
                u.incrementUnread(roomId);
            }
        });

        broadcastExecutor.submit(() -> broadcastToRoom(roomId, msg));
    }

    // ── Reactions ─────────────────────────────────────────────────────────────

    @Async("virtualThreadExecutor")
    public void toggleReaction(String sessionId, String messageId, String emoji) {
        UserSession user = sessionById.get(sessionId);
        ChatMessage msg = messageIndex.get(messageId);
        if (user == null || msg == null) return;

        Map<String, List<String>> updatedReactions = msg.toggleReaction(emoji, user.getUsername());

        ChatMessage reactionEvent = ChatMessage.reactionEvent(messageId, msg.getRoom(), updatedReactions);
        broadcastToRoom(msg.getRoom(), reactionEvent);
    }

    // ── Edit Message ──────────────────────────────────────────────────────────

    @Async("virtualThreadExecutor")
    public void editMessage(String sessionId, String messageId, String newContent) {
        UserSession user = sessionById.get(sessionId);
        ChatMessage msg = messageIndex.get(messageId);
        if (user == null || msg == null) return;

        // Only author can edit
        if (!msg.getSender().equals(user.getUsername())) return;

        String sanitized = sanitize(newContent);
        if (sanitized.isBlank()) return;

        msg.edit(sanitized);
        broadcastToRoom(msg.getRoom(), ChatMessage.editEvent(messageId, sanitized, msg.getRoom()));
    }

    // ── Delete Message ────────────────────────────────────────────────────────

    @Async("virtualThreadExecutor")
    public void deleteMessage(String sessionId, String messageId) {
        UserSession user = sessionById.get(sessionId);
        ChatMessage msg = messageIndex.get(messageId);
        if (user == null || msg == null) return;

        if (!msg.getSender().equals(user.getUsername())) return;

        msg.markDeleted();
        broadcastToRoom(msg.getRoom(), ChatMessage.deleteEvent(messageId, msg.getRoom()));
    }

    // ── Direct Message ────────────────────────────────────────────────────────

    @Async("virtualThreadExecutor")
    public void sendDM(String sessionId, String targetUsername, String content) {
        UserSession sender = sessionById.get(sessionId);
        if (sender == null) return;

        if (!rateLimiter.tryConsume(sessionId)) {
            sendErrorToUser(sessionId, "⚠️ Rate limit exceeded.");
            return;
        }

        String sanitized = sanitize(content);
        if (sanitized.isBlank()) return;

        ChatMessage dm = ChatMessage.dm(
            sender.getUsername(), sender.getAvatar(), sender.getColor(),
            sanitized, targetUsername
        );

        // Deliver to target
        String targetSessionId = sessionByUser.get(targetUsername.toLowerCase());
        if (targetSessionId != null) {
            messagingTemplate.convertAndSendToUser(targetSessionId, "/queue/dm", dm);
        }

        // Echo back to sender
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/dm", dm);
    }

    // ── Status Update ─────────────────────────────────────────────────────────

    public void updateStatus(String sessionId, String status) {
        UserSession user = sessionById.get(sessionId);
        if (user == null) return;
        try {
            user.setStatus(UserSession.Status.valueOf(status.toUpperCase()));
            broadcastUserList();
        } catch (IllegalArgumentException ignored) {}
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public List<ChatMessage> search(String roomId, String query) {
        if (query == null || query.isBlank()) return List.of();
        String lq = query.toLowerCase();
        CopyOnWriteArrayList<ChatMessage> roomHistory = history.get(roomId);
        if (roomHistory == null) return List.of();

        return roomHistory.stream()
            .filter(m -> m.getContent() != null
                && m.getContent().toLowerCase().contains(lq)
                && !m.isDeleted())
            .collect(Collectors.toList());
    }

    // ── Typing ────────────────────────────────────────────────────────────────

    @Async("virtualThreadExecutor")
    public void broadcastTyping(String sessionId) {
        UserSession user = sessionById.get(sessionId);
        if (user == null) return;
        messagingTemplate.convertAndSend(
            "/topic/room/" + user.getCurrentRoom() + "/typing",
            ChatMessage.typing(user.getUsername(), user.getCurrentRoom())
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcastToRoom(String roomId, ChatMessage message) {
        messagingTemplate.convertAndSend("/topic/room/" + roomId, message);
    }

    private void sendHistoryToUser(String sessionId, String roomId) {
        List<ChatMessage> h = history.getOrDefault(roomId, new CopyOnWriteArrayList<>());
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/history", h);
    }

    private void sendErrorToUser(String sessionId, String errorMsg) {
        messagingTemplate.convertAndSendToUser(sessionId, "/queue/errors",
            Map.of("error", errorMsg));
    }

    private void broadcastUserList() {
        broadcastExecutor.submit(() ->
            messagingTemplate.convertAndSend("/topic/users", getUsersPayload())
        );
    }

    private void detectAndNotifyMentions(String content, ChatMessage msg,
                                          String roomId, String senderName) {
        // Find all @username tokens
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@(\\w+)").matcher(content);
        while (m.find()) {
            String mentionedName = m.group(1).toLowerCase();
            if (mentionedName.equals(senderName.toLowerCase())) continue;
            String targetSession = sessionByUser.get(mentionedName);
            if (targetSession != null) {
                messagingTemplate.convertAndSendToUser(targetSession, "/queue/mention",
                    Map.of("messageId", msg.getId(), "room", roomId,
                           "from", senderName, "content", truncate(content, 100)));
            }
        }
    }

    private String sanitize(String input) {
        if (input == null) return "";
        return input.trim()
            .replace("<script", "&lt;script")
            .replace("</script", "&lt;/script")
            .replace("javascript:", "");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ── Payload builders ──────────────────────────────────────────────────────

    private List<Map<String, Object>> getUsersPayload() {
        return sessionById.values().stream().map(u -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("username", u.getUsername());
            map.put("avatar", u.getAvatar());
            map.put("color", u.getColor());
            map.put("currentRoom", u.getCurrentRoom());
            map.put("online", u.isOnline());
            map.put("status", u.getStatus().name());
            return map;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> getAllRoomsPayload() {
        return chatRooms.values().stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getId());
            map.put("name", r.getName());
            map.put("description", r.getDescription());
            map.put("icon", r.getIcon());
            map.put("activeUsers", r.getActiveUserCount());
            map.put("messageCount", r.getMessageCount());
            return map;
        }).collect(Collectors.toList());
    }

    // ── Public Accessors ──────────────────────────────────────────────────────

    public Collection<ChatRoom> getAllRooms() { return chatRooms.values(); }
    public Optional<ChatRoom> getRoom(String id) { return Optional.ofNullable(chatRooms.get(id)); }
    public Collection<UserSession> getActiveUsers() { return sessionById.values(); }
    public int getOnlineUserCount() { return sessionById.size(); }
    public List<ChatMessage> getRoomHistory(String roomId) {
        return history.getOrDefault(roomId, new CopyOnWriteArrayList<>());
    }
    public Optional<UserSession> getUserBySession(String sessionId) {
        return Optional.ofNullable(sessionById.get(sessionId));
    }
    public Optional<ChatMessage> getMessage(String messageId) {
        return Optional.ofNullable(messageIndex.get(messageId));
    }
}
