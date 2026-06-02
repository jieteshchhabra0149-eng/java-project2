package com.chatapp.controller;

import com.chatapp.model.ChatMessage;
import com.chatapp.model.ChatRoom;
import com.chatapp.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API — v2.
 *
 * New endpoints:
 *  GET  /api/rooms/{id}/search?q=term   — full-text search in a room
 *  GET  /api/messages/{id}              — get a single message by ID
 *  PUT  /api/rooms                      — update room info (name/desc)
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    private final ChatService chatService;

    public ApiController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<Map<String, Object>>> getRooms() {
        List<Map<String, Object>> rooms = chatService.getAllRooms().stream()
            .map(this::roomToMap)
            .collect(Collectors.toList());
        return ResponseEntity.ok(rooms);
    }

    @PostMapping("/rooms")
    public ResponseEntity<Map<String, Object>> createRoom(@RequestBody Map<String, String> body) {
        String id   = body.getOrDefault("id", UUID.randomUUID().toString().substring(0, 8));
        String name = body.getOrDefault("name", "New Room").trim();
        String desc = body.getOrDefault("description", "");
        String icon = body.getOrDefault("icon", "💬");

        if (name.isBlank()) return ResponseEntity.badRequest().build();

        ChatRoom room = chatService.createRoom(id, name, desc, icon);
        return ResponseEntity.ok(roomToMap(room));
    }

    @GetMapping("/rooms/{id}/history")
    public ResponseEntity<List<ChatMessage>> getRoomHistory(@PathVariable String id) {
        return ResponseEntity.ok(chatService.getRoomHistory(id));
    }

    /** Full-text search in a room's message history. */
    @GetMapping("/rooms/{id}/search")
    public ResponseEntity<List<ChatMessage>> searchMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String q) {
        return ResponseEntity.ok(chatService.search(id, q));
    }

    /** Get a single message by ID (for context/linking). */
    @GetMapping("/messages/{id}")
    public ResponseEntity<ChatMessage> getMessage(@PathVariable String id) {
        return chatService.getMessage(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getUsers() {
        List<Map<String, Object>> users = chatService.getActiveUsers().stream()
            .map(u -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username",    u.getUsername());
                m.put("avatar",      u.getAvatar());
                m.put("color",       u.getColor());
                m.put("currentRoom", u.getCurrentRoom());
                m.put("online",      u.isOnline());
                m.put("status",      u.getStatus().name());
                return m;
            })
            .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("onlineUsers",  chatService.getOnlineUserCount());
        stats.put("totalRooms",   chatService.getAllRooms().size());
        stats.put("serverTime",   new Date());
        stats.put("javaVersion",  System.getProperty("java.version"));
        stats.put("threadModel",  "Virtual Threads (Java 21)");
        return ResponseEntity.ok(stats);
    }

    private Map<String, Object> roomToMap(ChatRoom room) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          room.getId());
        m.put("name",        room.getName());
        m.put("description", room.getDescription());
        m.put("icon",        room.getIcon());
        m.put("activeUsers", room.getActiveUserCount());
        m.put("messageCount",room.getMessageCount());
        return m;
    }
}
