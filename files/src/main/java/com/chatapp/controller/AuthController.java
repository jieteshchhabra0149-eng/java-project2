package com.chatapp.controller;

import com.chatapp.dto.AuthDtos;
import com.chatapp.entity.UserAccount;
import com.chatapp.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.AuthResponse> register(
        @Valid @RequestBody AuthDtos.RegisterRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        AuthDtos.AuthResponse response = authService.register(request);
        persistSession(httpRequest, httpResponse);
        return response.success()
            ? ResponseEntity.ok(response)
            : ResponseEntity.badRequest().body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(
        @Valid @RequestBody AuthDtos.LoginRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        AuthDtos.AuthResponse response = authService.login(request);
        persistSession(httpRequest, httpResponse);
        return response.success()
            ? ResponseEntity.ok(response)
            : ResponseEntity.status(401).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        return authService.currentUser()
            .map(u -> ResponseEntity.ok(userMap(u)))
            .orElse(ResponseEntity.status(401).build());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        request.getSession(false);
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    private void persistSession(HttpServletRequest request, HttpServletResponse response) {
        var context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null) {
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        }
    }

    private Map<String, Object> userMap(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("avatar", u.getAvatar());
        m.put("color", u.getColor());
        return m;
    }
}
