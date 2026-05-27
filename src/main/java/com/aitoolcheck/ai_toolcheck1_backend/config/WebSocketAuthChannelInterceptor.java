package com.aitoolcheck.ai_toolcheck1_backend.config;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UserStatus;
import com.aitoolcheck.ai_toolcheck1_backend.security.CustomUserDetails;
import com.aitoolcheck.ai_toolcheck1_backend.security.CustomUserDetailsService;
import com.aitoolcheck.ai_toolcheck1_backend.security.JwtService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP channel interceptor that enforces JWT authentication on CONNECT frames
 * and project-level authorization on sensitive SUBSCRIBE destinations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern TEST_RUN_TOPIC_PATTERN = Pattern.compile(
            "^/topic/projects/([0-9a-fA-F-]{36})/test-runs/([0-9a-fA-F-]{36})$");

    private final JwtService jwtService;
    private final CustomUserDetailsService customUserDetailsService;
    private final ProjectAccessService projectAccessService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (accessor.getCommand() == StompCommand.CONNECT) {
            Authentication authentication = authenticateConnect(accessor);
            StompHeaderAccessor mutableAccessor = StompHeaderAccessor.wrap(message);
            mutableAccessor.setUser(authentication);
            return MessageBuilder.createMessage(message.getPayload(), mutableAccessor.getMessageHeaders());
        }

        if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            authorizeSubscribe(accessor);
        }

        return message;
    }

    private Authentication authenticateConnect(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (token == null) {
            log.warn("[WebSocketAuth] STOMP CONNECT rejected: missing Authorization header");
            throw new IllegalArgumentException("Missing or invalid Authorization header for WebSocket CONNECT");
        }

        try {
            if (!jwtService.isAccessTokenValid(token)) {
                log.warn("[WebSocketAuth] STOMP CONNECT rejected: invalid or expired token");
                throw new IllegalArgumentException("Invalid or expired JWT token");
            }

            UUID userId = jwtService.extractUserId(token);
            CustomUserDetails userDetails = customUserDetailsService.loadUserById(userId);

            if (userDetails.getUser().getStatus() != UserStatus.ACTIVE) {
                log.warn("[WebSocketAuth] STOMP CONNECT rejected: user {} is not ACTIVE", userId);
                throw new IllegalArgumentException("User account is not active");
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );

            log.debug("[WebSocketAuth] STOMP CONNECT accepted for user={}", userDetails.getUsername());
            return authentication;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[WebSocketAuth] STOMP CONNECT rejected: token validation error: {}", e.getMessage());
            throw new IllegalArgumentException("WebSocket authentication failed");
        }
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("Missing STOMP subscription destination");
        }

        Authentication authentication = authenticatedPrincipal(accessor);
        if (authentication == null) {
            log.warn("[WebSocketAuth] STOMP SUBSCRIBE rejected: unauthenticated destination={}", destination);
            throw new IllegalArgumentException("Authentication is required for WebSocket SUBSCRIBE");
        }

        if (destination.startsWith("/user/")) {
            return;
        }

        if (destination.startsWith("/queue/")) {
            log.warn("[WebSocketAuth] STOMP SUBSCRIBE rejected: direct queue destination={}", destination);
            throw new IllegalArgumentException("Subscribe through /user/queue destinations");
        }

        Matcher testRunTopic = TEST_RUN_TOPIC_PATTERN.matcher(destination);
        if (testRunTopic.matches()) {
            UUID projectId = UUID.fromString(testRunTopic.group(1));
            runWithSecurityContext(authentication, () -> projectAccessService.requireCanViewProject(projectId));
            return;
        }

        if (destination.startsWith("/topic/")) {
            log.warn("[WebSocketAuth] STOMP SUBSCRIBE rejected: unauthorized topic destination={}", destination);
            throw new IllegalArgumentException("Unauthorized WebSocket topic subscription");
        }
    }

    private Authentication authenticatedPrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof Authentication authentication && authentication.isAuthenticated()) {
            return authentication;
        }
        return null;
    }

    private void runWithSecurityContext(Authentication authentication, Runnable runnable) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        try {
            SecurityContextHolder.setContext(context);
            runnable.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private String extractToken(StompHeaderAccessor accessor) {
        List<String> authHeaders = accessor.getNativeHeader("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) {
            return null;
        }
        String header = authHeaders.get(0);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isBlank() ? null : token;
    }
}
