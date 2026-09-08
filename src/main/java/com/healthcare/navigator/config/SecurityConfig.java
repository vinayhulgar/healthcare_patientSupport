package com.healthcare.navigator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring Security configuration for the Healthcare Care Navigator.
 *
 * <p>Configures the application as an OAuth2 JWT resource server with:
 * <ul>
 *   <li>Permit-all for {@code /actuator/health} (liveness/readiness probe)</li>
 *   <li>JWT authentication required for all other requests (→ 401 on failure)</li>
 *   <li>RBAC via a custom JWT role converter — callers must hold
 *       {@code PATIENT_SUPPORT_USER} or {@code PATIENT_SUPPORT_ADMIN} (→ 403)</li>
 *   <li>Stateless session (no HTTP session created)</li>
 * </ul>
 *
 * <p>JWT validation (exp, iss, aud, signature) is handled automatically by
 * Spring Security's OAuth2 resource server using the JWKS endpoint configured
 * in {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}.
 *
 * <p>Requirements: 1.7, 1.8, 12.1, 12.2, 12.3, 12.4 | Design: §2.9
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Configures the {@link SecurityFilterChain} with JWT resource-server protection.
     *
     * <ul>
     *   <li>{@code /actuator/health} — fully public (no auth required)</li>
     *   <li>All other requests — require a valid JWT bearer token</li>
     *   <li>Missing/invalid/expired JWT → HTTP 401 via {@code authenticationEntryPoint}</li>
     *   <li>Valid JWT but insufficient role → HTTP 403 via {@code accessDeniedHandler}</li>
     * </ul>
     *
     * @param http Spring Security's {@link HttpSecurity} builder
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security configuration cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Stateless API — no HTTP session needed
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // CSRF not applicable for a stateless JWT API
            .csrf(csrf -> csrf.disable())

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())

            // OAuth2 JWT resource server
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter())))

            // 401 for missing/invalid JWT; 403 for insufficient role
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write(
                        new ObjectMapper().writeValueAsString(
                            Map.of("error", "Unauthorized",
                                   "message", "A valid JWT bearer token is required.")));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write(
                        new ObjectMapper().writeValueAsString(
                            Map.of("error", "Forbidden",
                                   "message", "Insufficient role to access this resource.")));
                }));

        return http.build();
    }

    /**
     * Custom JWT → {@link AbstractAuthenticationToken} converter that maps the
     * {@code roles} claim in the JWT to Spring Security {@link GrantedAuthority}
     * objects with the {@code ROLE_} prefix.
     *
     * <p>Supports callers holding {@code PATIENT_SUPPORT_USER} or
     * {@code PATIENT_SUPPORT_ADMIN}. The roles claim may be a {@link List} of
     * strings. If the claim is absent or not a list, an empty authority set is
     * returned (which will trigger a 403 downstream).
     *
     * @return a {@link Converter} from {@link Jwt} to {@link AbstractAuthenticationToken}
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> roleConverter() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = extractRoles(jwt).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
            return new JwtAuthenticationToken(jwt, authorities);
        };
    }

    /**
     * Extracts the {@code roles} claim from the JWT as a list of strings.
     *
     * <p>If the claim is absent or not a {@code List}, returns an empty list
     * so that the caller fails the role check gracefully with HTTP 403.
     *
     * @param jwt the validated JWT
     * @return list of role strings from the {@code roles} claim
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Jwt jwt) {
        Object rolesClaim = jwt.getClaims().get("roles");
        if (rolesClaim instanceof List<?> rolesList) {
            return rolesList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toList());
        }
        return List.of();
    }
}
