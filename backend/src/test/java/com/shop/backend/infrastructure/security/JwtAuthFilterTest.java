package com.shop.backend.infrastructure.security;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    // throwaway keys for this test only
    private final JwtService jwtService = new JwtService("unit-test-only-secret-key-0123456789-abcdefghij");
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService, userRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private String tokenFor(Role role) {
        User user = new User("member01", null, "hash", role);
        ReflectionTestUtils.setField(user, "id", "user-1");
        return jwtService.generateToken(user);
    }

    private MockFilterChain run(String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void 성공_유효한_토큰과_활성_사용자면_인증한다() throws Exception {
        when(userRepository.existsByIdAndDeletedAtIsNull("user-1")).thenReturn(true);

        MockFilterChain chain = run("Bearer " + tokenFor(Role.USER));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("user-1");
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        assertThat(chain.getRequest()).isNotNull(); // the chain continued
    }

    @Test
    void 실패_유효한_토큰이어도_탈퇴했거나_없는_사용자면_인증하지_않고_체인은_계속된다() throws Exception {
        when(userRepository.existsByIdAndDeletedAtIsNull("user-1")).thenReturn(false);

        MockFilterChain chain = run("Bearer " + tokenFor(Role.USER));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
        verify(userRepository).existsByIdAndDeletedAtIsNull("user-1");
    }

    @Test
    void 실패_무효한_토큰이면_사용자를_조회하지_않고_인증하지_않는다() throws Exception {
        run("Bearer not-a-jwt");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void 실패_서명이_다른_토큰이면_사용자를_조회하지_않는다() throws Exception {
        String forged = new JwtService("another-unit-test-secret-key-0123456789-xyz").generateToken(
                new User("member01", null, "hash", Role.ADMIN));

        run("Bearer " + forged);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void 실패_토큰이_없으면_사용자를_조회하지_않고_인증하지_않는다() throws Exception {
        run(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(userRepository);
    }
    @Test
    void 실패_사용자_조회가_DB_오류면_401이_아니라_503과_안내_메시지이고_체인은_진행하지_않는다() throws Exception {
        when(userRepository.existsByIdAndDeletedAtIsNull("user-1"))
                .thenThrow(new DataAccessResourceFailureException("connection refused", new SQLException("down", "08006")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenFor(Role.USER));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"error\":\"잠시 후 다시 시도해주세요.\"}");
        assertThat(chain.getRequest()).isNull(); // chain not continued
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 성공_DB가_정상이면_503이_아니라_체인이_진행된다() throws Exception {
        when(userRepository.existsByIdAndDeletedAtIsNull("user-1")).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenFor(Role.USER));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
