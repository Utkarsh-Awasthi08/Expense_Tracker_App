package org.example.Service;

import org.example.Entities.UserInfo;
import org.example.Entities.UserRole;
import org.example.Repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDetailsServiceImplTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final UserDetailsServiceImpl service = new UserDetailsServiceImpl(repository);

    private static UserInfo user(String phone) {
        UserInfo user = new UserInfo("0b0e6f0e-6b8e-4c2f-8d0a-3f7d6f1d2a11", phone, Instant.now());
        user.getRoles().add(new UserRole("ROLE_USER"));
        return user;
    }

    @Test
    void anExactMatchIsLoaded() {
        when(repository.findByPhoneNumber("+919876543210")).thenReturn(Optional.of(user("+919876543210")));

        UserDetails details = service.loadUserByUsername("+919876543210");

        assertThat(details.getUsername()).isEqualTo("+919876543210");
    }

    @Test
    void anUnknownUserIsNotFound() {
        when(repository.findByPhoneNumber("+919999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("+919999999999")).isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void aRowThatOnlyCollatesEqualIsTreatedAsUnknown() {
        when(repository.findByPhoneNumber("+919876543210")).thenReturn(Optional.of(user("+919876543210")));

        assertThatThrownBy(() -> service.loadUserByUsername("+919876543210 "))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User not found");
    }
}
