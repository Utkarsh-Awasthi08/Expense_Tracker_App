package org.example.Repository;

import org.example.Entities.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserInfo, String> {

    /** Look up by canonical E.164 phone number (see {@link org.example.Request.InputNormalizer}). */
    Optional<UserInfo> findByPhoneNumber(String phoneNumber);
}
