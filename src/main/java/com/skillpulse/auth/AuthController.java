package com.skillpulse.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Collections;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Validated
@RestController
public class AuthController {
    private final AuthService authService;
    private final PasswordRecoveryService passwordRecoveryService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(AuthService authService, PasswordRecoveryService passwordRecoveryService,
                          AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository) {
        this.authService = authService;
        this.passwordRecoveryService = passwordRecoveryService;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/api/auth/register")
    public AuthDtos.AuthResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                          HttpServletRequest servletRequest, HttpServletResponse response) {
        AuthDtos.AuthResponse result = authService.register(request);
        authenticate(request.getEmail(), request.getPassword(), servletRequest, response);
        return result;
    }

    @PostMapping("/api/auth/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                       HttpServletRequest servletRequest, HttpServletResponse response) {
        AuthDtos.AuthResponse result = authService.login(request);
        authenticate(request.getEmail(), request.getPassword(), servletRequest, response);
        return result;
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return Collections.<String, Object>singletonMap("ok", true);
    }

    @PostMapping("/api/auth/change-password")
    public Map<String, Object> changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request) {
        authService.changePassword(request);
        return Collections.<String, Object>singletonMap("message", "Password updated successfully.");
    }

    @PostMapping("/api/auth/delete-account")
    public Map<String, Object> deleteAccount(@Valid @RequestBody AuthDtos.DeleteAccountRequest request) {
        authService.deleteAccount(request);
        return Collections.<String, Object>singletonMap("message", "Account deleted successfully.");
    }

    @PostMapping("/api/auth/update-profile")
    public AuthDtos.AuthResponse updateProfile(@Valid @RequestBody AuthDtos.UpdateProfileRequest request) {
        return authService.updateProfile(request);
    }

    @PostMapping("/api/auth/forgot-password")
    public Map<String, Object> forgotPassword(@Valid @RequestBody PasswordRecoveryDtos.ForgotPasswordRequest request) {
        passwordRecoveryService.requestReset(request.getEmail());
        return Collections.<String, Object>singletonMap("message",
                "If an account exists for this email, a reset link has been sent.");
    }

    @PostMapping("/api/auth/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody PasswordRecoveryDtos.ResetPasswordRequest request) {
        passwordRecoveryService.resetPassword(request.getToken(), request.getNewPassword());
        return Collections.<String, Object>singletonMap("message", "Password reset successfully. You can now sign in.");
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<?> me() {
        return authService.findByToken(null)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(new AuthDtos.AuthResponse("cookie-session", user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Collections.singletonMap("message", "Session expired. Please sign in again.")));
    }

    private void authenticate(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email.trim().toLowerCase(), password));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

}
