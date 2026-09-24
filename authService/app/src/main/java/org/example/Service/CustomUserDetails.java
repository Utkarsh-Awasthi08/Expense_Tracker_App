package org.example.Service;

import org.example.Entities.UserInfo;
import org.example.Entities.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable view of a user for Spring Security contexts. Since authentication is now OTP-only
 * there is no password to expose. The "username" for Spring Security purposes is the phone number.
 * Role names are used verbatim as authorities (e.g. ROLE_USER).
 */
public class CustomUserDetails implements UserDetails {

    private final String userId;
    /** The canonical E.164 phone number; used as the Spring Security "username". */
    private final String phoneNumber;
    private final List<GrantedAuthority> authorities;

    public CustomUserDetails(UserInfo user) {
        this.userId = user.getUserId();
        this.phoneNumber = user.getPhoneNumber();
        this.authorities = user.getRoles().stream()
                .map(UserRole::getRoleName)
                .sorted(Comparator.naturalOrder())
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }

    public String getUserId() {
        return userId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * OTP-only: there is no stored password. Returns an empty string so the class compiles; it is
     * never passed to a PasswordEncoder because DaoAuthenticationProvider is no longer wired.
     */
    @Override
    public String getPassword() {
        return "";
    }

    /** The phone number is the unique identity for Spring Security. */
    @Override
    public String getUsername() {
        return phoneNumber;
    }
}
