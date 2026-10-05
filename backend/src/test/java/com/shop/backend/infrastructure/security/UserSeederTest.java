package com.shop.backend.infrastructure.security;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSeederTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private UserSeeder seeder(String adminEmail, String adminPw, String sellerEmail, String sellerPw) {
        when(passwordEncoder.encode(any())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
        return new UserSeeder(userRepository, passwordEncoder, adminEmail, adminPw, sellerEmail, sellerPw);
    }

    @Test
    void 성공_이메일과_비밀번호가_모두_있으면_해당_역할로_계정을_만든다() {
        seeder("boss@example.com", "pw-1", "shop@example.com", "pw-2").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(saved.capture());
        List<User> users = saved.getAllValues();
        assertThat(users).extracting(User::getEmail).containsExactly("boss@example.com", "shop@example.com");
        assertThat(users).extracting(User::getRole).containsExactly(Role.ADMIN, Role.SELLER);
        assertThat(users).extracting(User::getPasswordHash).containsExactly("hash:pw-1", "hash:pw-2");
    }

    @Test
    void 성공_이미_있는_이메일은_다시_만들지_않는다() {
        when(userRepository.existsByEmail("boss@example.com")).thenReturn(true);

        seeder("boss@example.com", "pw-1", "", "").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void 실패_관리자와_판매자_이메일이_같으면_기동을_막는다() {
        assertThatThrownBy(() -> seeder("same@example.com", "pw-1", "SAME@example.com", "pw-2").run(null))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void 실패_이메일이나_비밀번호가_비어_있으면_만들지_않는다() {
        seeder("", "pw-1", "shop@example.com", "").run(null);

        verify(userRepository, never()).save(any());
    }
}
