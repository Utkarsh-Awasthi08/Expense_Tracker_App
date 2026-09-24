package org.example.Entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** A user, identified by phone number. There is no password: identity is proven by OTP at verify time only. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "users")
public class UserInfo {

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    /** Canonical E.164-ish form (see {@link org.example.Request.InputNormalizer}); unique, never blank. */
    @Column(name = "phone_number", length = 20, nullable = false, unique = true)
    private String phoneNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<UserRole> roles = new HashSet<>();

    public UserInfo(String userId, String phoneNumber, Instant createdAt) {
        this.userId = userId;
        this.phoneNumber = phoneNumber;
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof UserInfo other && userId != null && userId.equals(other.userId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }
}
