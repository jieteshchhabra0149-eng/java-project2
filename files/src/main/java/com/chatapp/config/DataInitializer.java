package com.chatapp.config;

import com.chatapp.entity.UserAccount;
import com.chatapp.repository.UserAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserAccountRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!userRepository.existsByUsername("demo")) {
            UserAccount demo = new UserAccount();
            demo.setUsername("demo");
            demo.setPasswordHash(passwordEncoder.encode("demo123"));
            demo.setDisplayName("Demo User");
            demo.setAvatar("🚀");
            demo.setColor("#00D4FF");
            userRepository.save(demo);
        }
    }
}
