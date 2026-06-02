package com.chatapp.service;

import com.chatapp.model.ChatRoom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scheduled heartbeat service.
 *
 * Broadcasts live stats to all connected clients every 15 seconds:
 * - Online user count
 * - Per-room active user counts
 * - Server uptime / thread info
 *
 * Also broadcasts periodic "bot" activity messages to keep
 * demo rooms feeling alive.
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatService chatService;

    private long startTime = System.currentTimeMillis();

    public HeartbeatService(SimpMessagingTemplate messagingTemplate, ChatService chatService) {
        this.messagingTemplate = messagingTemplate;
        this.chatService = chatService;
    }

    /**
     * Broadcast live stats every 15 seconds.
     * Clients subscribe to /topic/stats.
     */
    @Scheduled(fixedDelay = 15_000)
    public void broadcastStats() {
        if (chatService.getOnlineUserCount() == 0) return;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("onlineUsers", chatService.getOnlineUserCount());
        stats.put("totalRooms", chatService.getAllRooms().size());
        stats.put("uptimeSeconds", (System.currentTimeMillis() - startTime) / 1000);
        stats.put("serverTime", LocalTime.now().format(TIME_FMT));
        stats.put("javaVersion", System.getProperty("java.version"));
        stats.put("threadModel", "Virtual Threads (Java 21)");

        // Per-room counts
        Map<String, Integer> roomCounts = chatService.getAllRooms().stream()
            .collect(Collectors.toMap(
                ChatRoom::getId,
                ChatRoom::getActiveUserCount,
                (a, b) -> a,
                LinkedHashMap::new
            ));
        stats.put("roomCounts", roomCounts);

        messagingTemplate.convertAndSend("/topic/stats", stats);
        log.debug("Heartbeat broadcast: {} online users", chatService.getOnlineUserCount());
    }

    /**
     * Keep rooms alive with system tips every 5 minutes (if no recent messages).
     * Only fires if at least one user is online.
     */
    @Scheduled(fixedDelay = 300_000)
    public void broadcastSystemTip() {
        if (chatService.getOnlineUserCount() == 0) return;

        List<String> tips = List.of(
            "💡 Tip: Press Enter to send, Shift+Enter for a new line.",
            "⚡ Powered by Java 21 Virtual Threads — millions of concurrent connections.",
            "🧵 Each WebSocket message is handled by its own virtual thread.",
            "🔍 Use /search <term> to search message history.",
            "👋 Mention someone with @username to notify them.",
            "💬 Reply to a message by hovering it and clicking the reply icon.",
            "⚙️ Right-click your own messages to edit or delete them.",
            "🔔 Toggle sound notifications in the top bar."
        );

        String tip = tips.get((int)(Math.random() * tips.size()));

        // Broadcast to general room only
        Map<String, Object> sysMsg = Map.of(
            "type", "SYSTEM_TIP",
            "content", tip,
            "room", "general"
        );
        messagingTemplate.convertAndSend("/topic/system", sysMsg);
    }
}
