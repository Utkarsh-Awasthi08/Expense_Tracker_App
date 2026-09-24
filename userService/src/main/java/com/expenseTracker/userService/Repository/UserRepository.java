package com.expenseTracker.userService.Repository;

import com.expenseTracker.userService.Entities.UserInfo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends CrudRepository<UserInfo, Long> {

    Optional<UserInfo> findByUserId(String userId);

    /**
     * Same lookup as {@link #findByUserId} but as a locking read ({@code SELECT ... FOR UPDATE}): the row stays
     * locked until the surrounding transaction ends, so read-modify-write updates of one user serialize. Must be
     * called inside a transaction. The schema needs no version column for this.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserInfo u where u.userId = :userId")
    Optional<UserInfo> findByUserIdForUpdate(@Param("userId") String userId);
}
