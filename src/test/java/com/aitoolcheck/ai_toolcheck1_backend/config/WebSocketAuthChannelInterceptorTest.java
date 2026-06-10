package com.aitoolcheck.ai_toolcheck1_backend.config;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.security.CustomUserDetails;
import com.aitoolcheck.ai_toolcheck1_backend.security.CustomUserDetailsService;
import com.aitoolcheck.ai_toolcheck1_backend.security.JwtService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketAuthChannelInterceptorTest {

    @Test
    void connectWithoutAuthorizationIsRejected() {
        WebSocketAuthChannelInterceptor interceptor = interceptor();

        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(StompCommand.CONNECT, null, null), null));
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        JwtService jwtService = mock(JwtService.class);
        when(jwtService.isAccessTokenValid("bad")).thenReturn(false);
        WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
                jwtService,
                mock(CustomUserDetailsService.class),
                mock(ProjectAccessService.class));

        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message(StompCommand.CONNECT, "Bearer bad", null), null));
    }

    @Test
    void connectWithValidTokenIsAcceptedAndPrincipalNameMatchesEmail() {
        UUID userId = UUID.randomUUID();
        JwtService jwtService = mock(JwtService.class);
        CustomUserDetailsService userDetailsService = mock(CustomUserDetailsService.class);
        AppUser user = user(userId, "user@example.com");
        when(jwtService.isAccessTokenValid("good")).thenReturn(true);
        when(jwtService.extractUserId("good")).thenReturn(userId);
        when(userDetailsService.loadUserById(userId)).thenReturn(new CustomUserDetails(user));

        WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
                jwtService,
                userDetailsService,
                mock(ProjectAccessService.class));

        Message<?> result = interceptor.preSend(message(StompCommand.CONNECT, "Bearer good", null), null);
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);

        assertTrue(accessor.getUser() instanceof Authentication);
        assertEquals("user@example.com", accessor.getUser().getName());
    }

    @Test
    void subscribeToTestRunTopicRequiresProjectViewPermission() {
        ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
        WebSocketAuthChannelInterceptor interceptor = new WebSocketAuthChannelInterceptor(
                mock(JwtService.class),
                mock(CustomUserDetailsService.class),
                projectAccessService);
        UUID projectId = UUID.randomUUID();
        UUID testRunId = UUID.randomUUID();

        Message<?> message = message(
                StompCommand.SUBSCRIBE,
                null,
                "/topic/projects/" + projectId + "/test-runs/" + testRunId);
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new CustomUserDetails(user(UUID.randomUUID(), "user@example.com")),
                null,
                java.util.List.of()));

        interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null);

        verify(projectAccessService).requireCanViewProject(projectId);
    }

    @Test
    void subscribeToUnknownTopicIsRejected() {
        WebSocketAuthChannelInterceptor interceptor = interceptor();
        Message<?> message = authenticatedSubscribe("/topic/system/events");

        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message, null));
    }

    @Test
    void directQueueSubscribeIsRejected() {
        WebSocketAuthChannelInterceptor interceptor = interceptor();
        Message<?> message = authenticatedSubscribe("/queue/notifications");

        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(message, null));
    }

    private WebSocketAuthChannelInterceptor interceptor() {
        return new WebSocketAuthChannelInterceptor(
                mock(JwtService.class),
                mock(CustomUserDetailsService.class),
                mock(ProjectAccessService.class));
    }

    private Message<?> message(StompCommand command, String authorization, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> authenticatedSubscribe(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new CustomUserDetails(user(UUID.randomUUID(), "user@example.com")),
                null,
                java.util.List.of()));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private AppUser user(UUID id, String email) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRole(UserRole.MEMBER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
