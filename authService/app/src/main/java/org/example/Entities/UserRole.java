package org.example.Entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "roles")
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "role_name", length = 50, nullable = false)
    private String roleName;

    public UserRole(String roleName) {
        this.roleName = roleName;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof UserRole other && roleName != null && roleName.equals(other.roleName));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(roleName);
    }
}
