package com.chorus.observe.security;

import com.chorus.observe.config.ApiKeyAuthFilter;
import com.chorus.observe.model.ApiKey;
import com.chorus.observe.persistence.InMemoryApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest {

    private InMemoryApiKeyRepository apiKeyRepository;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        apiKeyRepository = new InMemoryApiKeyRepository();
        filter = new ApiKeyAuthFilter(apiKeyRepository, true);
    }

    @Test
    void shouldBypassPublicPathWithoutAuth() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        request.setRequestURI("/actuator/health");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldAuthenticateWithValidApiKey() throws ServletException, IOException {
        ApiKey key = new ApiKey(
            "key-1", "tenant-1", "user-1", "test-key",
            List.of("spans:read"), null, null, Instant.now(), null
        );
        apiKeyRepository.save(key);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> authName = new AtomicReference<>();
        AtomicReference<Set<String>> authAuthorities = new AtomicReference<>();
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null) {
                    authName.set(auth.getName());
                    authAuthorities.set(auth.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .collect(java.util.stream.Collectors.toSet()));
                }
            }
        };
        request.setRequestURI("/v1/traces");
        request.addHeader("X-API-Key", "key-1");

        filter.doFilter(request, response, chain);

        assertThat(authName.get()).isEqualTo("user-1");
        assertThat(authAuthorities.get()).containsExactly("spans:read");
    }

    @Test
    void shouldRejectRevokedApiKey() throws ServletException, IOException {
        ApiKey key = new ApiKey(
            "key-1", "tenant-1", "user-1", "test-key",
            List.of("spans:read"), null, null, Instant.now(), Instant.now()
        );
        apiKeyRepository.save(key);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        request.setRequestURI("/v1/traces");
        request.addHeader("X-API-Key", "key-1");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void shouldContinueChainWhenApiKeyMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        request.setRequestURI("/v1/traces");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void shouldClearSecurityContextAfterRequest() throws ServletException, IOException {
        ApiKey key = new ApiKey(
            "key-1", "tenant-1", "user-1", "test-key",
            List.of("spans:read"), null, null, Instant.now(), null
        );
        apiKeyRepository.save(key);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        request.setRequestURI("/v1/traces");
        request.addHeader("X-API-Key", "key-1");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.isAuthenticated()).isFalse();
    }
}
