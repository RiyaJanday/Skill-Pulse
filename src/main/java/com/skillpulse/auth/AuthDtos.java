package com.skillpulse.auth;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class AuthDtos {
    public static class RegisterRequest {
        @NotBlank
        private String name;
        @Email
        @NotBlank
        private String email;
        @Size(min = 8)
        private String password;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LoginRequest {
        @Email
        @NotBlank
        private String email;
        @NotBlank
        private String password;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LogoutRequest {
        private String token;
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class ChangePasswordRequest {
        @NotBlank
        private String token;
        @NotBlank
        private String currentPassword;
        @Size(min = 8)
        private String newPassword;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getCurrentPassword() { return currentPassword; }
        public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }

    public static class DeleteAccountRequest {
        @NotBlank
        private String token;
        @NotBlank
        private String password;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class UpdateProfileRequest {
        @NotBlank
        private String token;
        @NotBlank
        private String firstName;
        @NotBlank
        private String lastName;
        @Email
        @NotBlank
        private String email;
        private String phoneCountryCode;
        private String phoneNumber;
        private String profilePhoto;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPhoneCountryCode() { return phoneCountryCode; }
        public void setPhoneCountryCode(String phoneCountryCode) { this.phoneCountryCode = phoneCountryCode; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public String getProfilePhoto() { return profilePhoto; }
        public void setProfilePhoto(String profilePhoto) { this.profilePhoto = profilePhoto; }
    }

    public static class AuthResponse {
        private String token;
        private Long userId;
        private String name;
        private String firstName;
        private String lastName;
        private String email;
        private String phoneCountryCode;
        private String phoneNumber;
        private String profilePhoto;
        private String role;

        public AuthResponse(String token, AppUser user) {
            this.token = token;
            this.userId = user.getId();
            this.name = user.getName();
            this.firstName = user.getFirstName();
            this.lastName = user.getLastName();
            this.email = user.getEmail();
            this.phoneCountryCode = user.getPhoneCountryCode();
            this.phoneNumber = user.getPhoneNumber();
            this.profilePhoto = user.getProfilePhoto();
            this.role = user.getRole().name();
        }

        public String getToken() { return token; }
        public Long getUserId() { return userId; }
        public String getName() { return name; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getEmail() { return email; }
        public String getPhoneCountryCode() { return phoneCountryCode; }
        public String getPhoneNumber() { return phoneNumber; }
        public String getProfilePhoto() { return profilePhoto; }
        public String getRole() { return role; }
    }
}
