package com.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 24) String username,
        @NotBlank @Size(min = 6, max = 72) String password,
        @Size(max = 48) String displayName
    ) {}

    public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
    ) {}

    public record AuthResponse(
        boolean success,
        String username,
        String displayName,
        String avatar,
        String color,
        String message
    ) {}
}
