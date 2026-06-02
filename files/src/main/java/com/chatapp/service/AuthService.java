package com.chatapp.service;

import com.chatapp.dto.AuthDtos;
import com.chatapp.entity.UserAccount;
import com.chatapp.repository.UserAccountRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class AuthService {

    private static final List<String> AVATARS = List.of("🙂", "😎", "🦊", "🐱", "🚀", "⚡", "🌊", "🎯");
    private static final List<String> COLORS = List.of("#00D4FF", "#FFB347", "#00E5A0", "#B06EFF", "#FF4D6A");

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public AuthService(
        UserAccountRepository userRepository,
        PasswordEncoder passwordEncoder,
        AuthenticationManager authenticationManager
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String username = request.username().trim().toLowerCase();
        if (userRepository.existsByUsername(username)) {
            return new AuthDtos.AuthResponse(false, null, null, null, null, "Username already taken.");
        }

        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setDisplayName(
            request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName().trim()
                : username
        );
        account.setAvatar(AVATARS.get(Math.abs(username.hashCode()) % AVATARS.size()));
        account.setColor(COLORS.get(Math.abs(username.hashCode()) % COLORS.size()));
        userRepository.save(account);

        return login(new AuthDtos.LoginRequest(username, request.password()));
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    request.username().trim().toLowerCase(),
                    request.password()
                )
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            UserAccount account = userRepository.findByUsername(auth.getName()).orElseThrow();
            return new AuthDtos.AuthResponse(
                true,
                account.getUsername(),
                account.getDisplayName(),
                account.getAvatar(),
                account.getColor(),
                "Logged in successfully."
            );
        } catch (AuthenticationException e) {
            return new AuthDtos.AuthResponse(false, null, null, null, null, "Invalid username or password.");
        }
    }

    public Optional<UserAccount> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        return userRepository.findByUsername(auth.getName());
    }
}
