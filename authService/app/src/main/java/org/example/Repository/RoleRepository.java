package org.example.Repository;

import org.example.Entities.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<UserRole, Long>
{
    Optional<UserRole> findByRoleName(String roleName);
}
