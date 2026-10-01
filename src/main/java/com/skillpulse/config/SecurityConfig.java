package com.skillpulse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillpulse.auth.AppUser;
import com.skillpulse.auth.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.util.Collections;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository users) {
        return username -> {
            AppUser user = users.findByEmailIgnoreCase(username)
                    .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("Account not found."));
            return User.withUsername(user.getEmail()).password(user.getPasswordHash())
                    .roles(user.getRole().name()).build();
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("XSRF-TOKEN");
        csrf.setHeaderName("X-XSRF-TOKEN");
        http.csrf().csrfTokenRepository(csrf)
            // The UI is same-origin and the session cookie is SameSite=Strict. This keeps the
            // existing JSON client compatible; add X-XSRF-TOKEN headers before allowing cross-origin clients.
            .ignoringAntMatchers("/api/**")
            .and().sessionManagement().sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).sessionFixation().migrateSession()
            .and().authorizeRequests()
            .antMatchers("/api/admin/**").hasRole("ADMIN")
            .antMatchers("/api/dashboard/**", "/api/notifications/**", "/api/personalization/**", "/api/ai/**", "/api/chat/**", "/api/timetable/**", "/api/auth/me", "/api/auth/logout",
                    "/api/auth/change-password", "/api/auth/delete-account", "/api/auth/update-profile",
                    "/api/practice/plans", "/api/practice/attempts", "/api/practice/review/**",
                    "/api/practice/topics/*/assessment", "/api/ml/analyze").authenticated()
            .antMatchers("/**").permitAll()
            .and().exceptionHandling()
            .authenticationEntryPoint((request, response, ex) -> {
                response.setStatus(HttpStatus.UNAUTHORIZED.value()); response.setContentType("application/json");
                mapper.writeValue(response.getWriter(), new ApiErrorResponse(
                        401, "UNAUTHENTICATED", "Please sign in.", request.getRequestURI(), null));
            })
            .accessDeniedHandler((request, response, ex) -> {
                response.setStatus(HttpStatus.FORBIDDEN.value()); response.setContentType("application/json");
                mapper.writeValue(response.getWriter(), new ApiErrorResponse(
                        403, "ACCESS_DENIED", "Access denied.", request.getRequestURI(), null));
            })
            .and().formLogin().disable().httpBasic().disable().logout().disable();
        return http.build();
    }
}
