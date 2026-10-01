package com.skillpulse.admin;

import com.skillpulse.auth.AppUser;
import com.skillpulse.auth.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class AdminBootstrap implements CommandLineRunner {
    private final UserRepository users;
    private final String adminEmail;
    public AdminBootstrap(UserRepository users, @Value("${skillpulse.admin.email:}") String adminEmail) {
        this.users = users;
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
    }
    @Override public void run(String... args) {
        if (adminEmail.isEmpty()) return;
        Optional<AppUser> existing = users.findByEmailIgnoreCase(adminEmail);
        if (existing.isPresent() && !AppUser.Role.ADMIN.equals(existing.get().getRole())) {
            AppUser user = existing.get();
            user.setRole(AppUser.Role.ADMIN);
            users.save(user);
        }
    }
}
