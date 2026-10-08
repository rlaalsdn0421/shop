package com.shop.backend.infrastructure.security;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

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

    private UserSeeder seeder(String adminUser, String adminPw, String sellerUser, String sellerPw) {
        when(passwordEncoder.encode(any())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
        return new UserSeeder(userRepository, passwordEncoder, adminUser, adminPw, sellerUser, sellerPw);
    }

    @Test
    void 성공_아이디와_비밀번호가_모두_있으면_해당_역할로_계정을_만든다() {
        seeder("boss01", "pw-1", "shop01", "pw-2").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(saved.capture());
        List<User> users = saved.getAllValues();
        assertThat(users).extracting(User::getUsername).containsExactly("boss01", "shop01");
        assertThat(users).extracting(User::getEmail).containsOnlyNulls();
        assertThat(users).extracting(User::getBirthDate).containsOnlyNulls();
        assertThat(users).extracting(User::getRole).containsExactly(Role.ADMIN, Role.SELLER);
        assertThat(users).extracting(User::getPasswordHash).containsExactly("hash:pw-1", "hash:pw-2");
    }

    @Test
    void 성공_이미_있는_아이디는_다시_만들지_않는다() {
        when(userRepository.findByUsername("boss01")).thenReturn(Optional.of(new User("boss01", null, "h", Role.ADMIN)));

        seeder("boss01", "pw-1", "", "").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void 실패_관리자와_판매자_아이디가_같으면_기동을_막는다() {
        assertThatThrownBy(() -> seeder("same01", "pw-1", "SAME01", "pw-2").run(null))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void 실패_아이디나_비밀번호가_비어_있으면_만들지_않는다() {
        seeder("", "pw-1", "shop01", "").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void 실패_시드_아이디를_다른_역할의_계정이_쓰고_있으면_기동을_막고_저장하지_않는다() {
        when(userRepository.findByUsername("boss01"))
                .thenReturn(Optional.of(new User("boss01", "u@example.com", "h", Role.USER)));

        assertThatThrownBy(() -> seeder("boss01", "pw-1", "shop01", "pw-2").run(null))
                .isInstanceOf(IllegalStateException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void 성공_다른_인스턴스가_먼저_만들어_저장이_충돌해도_예외_없이_판매자_시드를_이어간다() {
        when(userRepository.findByUsername("boss01"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new User("boss01", null, "h", Role.ADMIN)));
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        seeder("boss01", "pw-1", "shop01", "pw-2").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(User::getRole).containsExactly(Role.ADMIN, Role.SELLER);
    }

    @Test
    void 실패_저장이_충돌했는데_계정이_여전히_없으면_원래_예외를_그대로_던진다() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("too long");
        when(userRepository.save(any(User.class))).thenThrow(violation);

        assertThatThrownBy(() -> seeder("boss01", "pw-1", "shop01", "pw-2").run(null))
                .isSameAs(violation);
    }

    @Test
    void 실패_저장이_충돌했는데_다른_역할의_계정이_생겼으면_기동을_막는다() {
        when(userRepository.findByUsername("boss01"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new User("boss01", "u@example.com", "h", Role.USER)));
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> seeder("boss01", "pw-1", "shop01", "pw-2").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Seed username is already used by an account with a different role");
    }
}
