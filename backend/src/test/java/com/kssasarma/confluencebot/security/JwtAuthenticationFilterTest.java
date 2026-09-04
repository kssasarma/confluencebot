package com.kssasarma.confluencebot.security;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserDetailsService users = mock(UserDetailsService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, users);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void restoresAuthenticationForAnAsyncSseRedispatch() throws Exception {
        authenticate(DispatcherType.ASYNC);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(users).loadUserByUsername("reader@example.com");
    }

    @Test
    void restoresAuthenticationForAnErrorRedispatch() throws Exception {
        authenticate(DispatcherType.ERROR);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(users).loadUserByUsername("reader@example.com");
    }

    private void authenticate(DispatcherType dispatcherType) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/stream");
        request.setDispatcherType(dispatcherType);
        request.addHeader("Authorization", "Bearer valid-token");
        when(jwtService.isTokenValid("valid-token")).thenReturn(true);
        when(jwtService.extractEmail("valid-token")).thenReturn("reader@example.com");
        when(users.loadUserByUsername("reader@example.com"))
                .thenReturn(User.withUsername("reader@example.com").password("unused").roles("USER").build());

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> { });
    }
}
