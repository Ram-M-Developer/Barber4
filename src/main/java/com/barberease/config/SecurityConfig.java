package com.barberease.config;

import com.barberease.security.JwtTokenFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtTokenFilter jwtTokenFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // ── Public static assets and auth pages ──
                .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers("/auth/**").permitAll()

                // ── Customer-accessible pages (auth guards are in JS) ──
                .requestMatchers("/customer/**").permitAll()

                // ── Admin-accessible pages (auth guards are in JS) ──
                .requestMatchers("/admin/**").permitAll()

                // ── WebSocket ──
                .requestMatchers("/ws", "/ws/**").permitAll()

                // ── Public API endpoints ──
                .requestMatchers("/api/auth/login", "/api/auth/register",
                        "/api/auth/admin/login", "/api/auth/forgot-password",
                        "/api/auth/reset-password", "/api/health").permitAll()

                // ── Public READ endpoints (GET only) ──
                .requestMatchers(HttpMethod.GET, "/api/services/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/appointments/slots", "/api/appointments/slots/**").permitAll()

                // ── Admin-only endpoints (BACKEND enforced) ──
                // These controllers also have verifyAdmin() internally, providing defense-in-depth
                .requestMatchers(
                        "/api/chairs/*/complete-service",
                        "/api/chairs/*/force-free",
                        "/api/queue/call-next",
                        "/api/queue/*/call",
                        "/api/queue/*/seat",
                        "/api/queue/*/skip",
                        "/api/queue/*/no-show",
                        "/api/waiting-chairs/**",
                        "/api/reports/**"
                ).authenticated()

                // ── All other /api/** require authentication ──
                .requestMatchers("/api/**").authenticated()

                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Collections.singletonList("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
