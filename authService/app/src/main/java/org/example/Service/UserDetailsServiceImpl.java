package org.example.Service;

import lombok.RequiredArgsConstructor;
import org.example.Entities.UserInfo;
import org.example.Repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads users by phone number for Spring Security contexts. With OTP-only authentication this
 * service is no longer called during login (OtpService issues tokens directly), but it remains
 * wired so that the resource-server filter can still resolve the principal from the JWT subject
 * via {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter}.
 *
 * <p>The "username" Spring Security asks for is the phone number stored in the JWT {@code username}
 * claim (set by {@link JwtService}).
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String phoneNumber) throws UsernameNotFoundException {
        UserInfo user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        if (!user.getPhoneNumber().equals(phoneNumber)) {
            throw new UsernameNotFoundException("User not found");
        }
        return new CustomUserDetails(user);
    }
}
