package com.skillpulse.admin;

import com.skillpulse.auth.AppUser;
import java.time.Instant;

public class AdminUserDtos {
    public static class RoleRequest {
        private String role;
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    public static class UserRow {
        private final Long id;
        private final String name;
        private final String email;
        private final String role;
        private final Instant createdAt;
        private final boolean currentUser;

        public UserRow(AppUser user, boolean currentUser) {
            this.id = user.getId();
            this.name = user.getName();
            this.email = user.getEmail();
            this.role = user.getRole().name();
            this.createdAt = user.getCreatedAt();
            this.currentUser = currentUser;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
        public Instant getCreatedAt() { return createdAt; }
        public boolean isCurrentUser() { return currentUser; }
    }
}
