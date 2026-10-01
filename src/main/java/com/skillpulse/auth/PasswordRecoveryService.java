package com.skillpulse.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

@Service
public class PasswordRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final AuthService auth;
    private final JavaMailSender mailSender;
    private final String baseUrl;
    private final String fromAddress;
    private final long expiryMinutes;

    public PasswordRecoveryService(UserRepository users,
                                   PasswordResetTokenRepository tokens,
                                   AuthService auth,
                                   JavaMailSender mailSender,
                                   @Value("${skillpulse.app.base-url}") String baseUrl,
                                   @Value("${skillpulse.mail.from}") String fromAddress,
                                   @Value("${skillpulse.password-reset.minutes:15}") long expiryMinutes) {
        this.users = users;
        this.tokens = tokens;
        this.auth = auth;
        this.mailSender = mailSender;
        this.baseUrl = baseUrl;
        this.fromAddress = fromAddress;
        this.expiryMinutes = expiryMinutes;
    }

    @Transactional
    public void requestReset(String submittedEmail) {
        String email = submittedEmail == null ? "" : submittedEmail.trim().toLowerCase(Locale.ROOT);
        Optional<AppUser> found = users.findByEmailIgnoreCase(email);
        if (!found.isPresent()) return;

        AppUser user = found.get();
        tokens.deleteByUserId(user.getId());
        String rawToken = generateToken();
        PasswordResetToken reset = new PasswordResetToken();
        reset.setUser(user);
        reset.setTokenHash(hash(rawToken));
        reset.setExpiresAt(Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES));
        tokens.save(reset);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(user.getEmail());
            message.setSubject("Reset your SkillPulse password");
            message.setText("Hello " + user.getName() + ",\n\n"
                    + "Use the link below to reset your SkillPulse password. This link expires in "
                    + expiryMinutes + " minutes and can be used only once.\n\n"
                    + normalizedBaseUrl() + "/reset-password.html?token=" + rawToken
                    + "\n\nIf you did not request this, you can ignore this email.");
            mailSender.send(message);
        } catch (RuntimeException ex) {
            log.error("Unable to send password reset email to user {}", user.getId(), ex);
        }
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken reset = tokens.findByTokenHashAndUsedAtIsNull(hash(rawToken))
                .orElseThrow(() -> new IllegalArgumentException("This reset link is invalid or has already been used."));
        if (reset.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("This reset link has expired. Request a new one.");
        }
        auth.setPasswordFromRecovery(reset.getUser(), newPassword);
        reset.setUsedAt(Instant.now());
        tokens.save(reset);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : hashed) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to secure the password reset token.", ex);
        }
    }

    private String normalizedBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
