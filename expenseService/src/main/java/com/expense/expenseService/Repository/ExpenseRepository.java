package com.expense.expenseService.Repository;

import com.expense.expenseService.Entities.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExpenseRepository extends CrudRepository<Expense, Long> {

    List<Expense> findByUserId(String userId);

    /** Newest transaction first; id breaks ties so the order is stable. Backed by idx_expense_user_txn_date. */
    List<Expense> findByUserIdOrderByTxnDateDescIdDesc(String userId);

    /**
     * The caller's expenses within an optional, inclusive txn_date range. The sort comes from the Pageable
     * (txn_date desc, id desc); every query is scoped by user_id, there is no way to list across users.
     */
    @Query("""
            select e from Expense e
            where e.userId = :userId
              and (:from is null or e.txnDate >= :from)
              and (:to is null or e.txnDate <= :to)
            """)
    Page<Expense> search(@Param("userId") String userId,
                         @Param("from") LocalDate from,
                         @Param("to") LocalDate to,
                         Pageable pageable);

    /** Newest 500 rows for the legacy alias. Keep the number in sync with ExpenseService.LEGACY_LIMIT. */
    List<Expense> findTop500ByUserIdOrderByTxnDateDescIdDesc(String userId);

    List<Expense> findByUserIdAndCreatedAtBetween(
            String userId,
            Instant startDate,
            Instant endDate
    );

    Optional<Expense> findByUserIdAndExternalId(String userId, String externalId);

    /** Dedup check for SMS-derived expenses; the (user_id, sms_hash) unique key is the real guard. */
    boolean existsByUserIdAndSmsHash(String userId, String smsHash);

    @Query("""
            select coalesce(sum(e.amount), 0) from Expense e
            where e.userId = :userId
              and e.txnDate >= :startOfMonth
            """)
    java.math.BigDecimal sumAmountByUserIdAndTxnDateGreaterThanEqual(@Param("userId") String userId, @Param("startOfMonth") LocalDate startOfMonth);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update Expense e set e.merchant = :aliasName where e.userId = :userId and e.merchant = :originalName")
    int updateMerchantName(@Param("userId") String userId, @Param("originalName") String originalName, @Param("aliasName") String aliasName);

    @Query("select coalesce(sum(e.amount), 0) from Expense e where e.userId = :userId and e.txnDate = :date")
    java.math.BigDecimal sumAmountByUserIdAndTxnDate(@Param("userId") String userId, @Param("date") LocalDate date);
}
