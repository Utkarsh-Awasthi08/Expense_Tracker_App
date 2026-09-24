package com.expense.expenseService;

import com.expense.expenseService.DTO.ExpenseDTO;
import com.expense.expenseService.Entities.Expense;
import com.expense.expenseService.Mapper.ExpenseMapper;
import com.expense.expenseService.Repository.ExpenseRepository;
import com.expense.expenseService.Service.ExpenseService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the whole application against a real MySQL 8.4 with Flyway and {@code ddl-auto=validate}.
 * This is also the context-load test: if an entity and the migration disagree, the context does not start.
 * Skipped (not failed) when Docker is unavailable. Kafka listeners are switched off, no broker is needed.
 * <p>
 * Not @Transactional on purpose: the unique-constraint tests must really commit and really collide.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class ExpensePersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static TimeZone originalTimeZone;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeAll
    static void useNonUtcJvmZone() {
        // Storage must be UTC regardless of where the service runs, so run the JVM in a zone that is not UTC.
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @AfterAll
    static void restoreJvmZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Autowired
    ExpenseService expenseService;

    @Autowired
    ExpenseRepository expenseRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExpenseMapper expenseMapper;

    @BeforeEach
    void cleanTable() {
        expenseRepository.deleteAll();
    }

    private static ExpenseDTO dto(String userId, String smsHash) {
        return ExpenseDTO.builder()
                .externalId(java.util.UUID.randomUUID().toString())
                .userId(userId)
                .smsHash(smsHash)
                .smsReceivedAt(Instant.parse("2026-03-14T08:15:30.123456Z"))
                .amount(new BigDecimal("1234.50"))
                .currency("INR")
                .merchant("Blue Tokai Coffee")
                .category("FOOD")
                .txnType("DEBIT")
                .accountLast4("4321")
                .txnDate(LocalDate.of(2026, 3, 13))
                .createdAt(Instant.parse("2026-03-14T09:45:10.654321Z"))
                .build();
    }

    @Test
    void flywayAppliedV1AndV2AndHibernateValidatedThem() {
        Integer v1 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = 1", Integer.class);
        Integer v2 = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success = 1", Integer.class);
        Integer versioned = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version is not null", Integer.class);

        assertEquals(1, v1);
        assertEquals(1, v2, "V2 adds CHECK (amount > 0)");
        assertEquals(2, versioned, "V1 and V2 exist at this stage");
    }

    @Test
    void theDatabaseItselfRefusesZeroAndNegativeAmounts() {
        // Straight JDBC: no entity, no service, no Amounts rule in the way. MySQL 8.4 enforces the V2 CHECK.
        for (String amount : new String[]{"0.00", "-0.01", "-1234.50"}) {
            DataAccessException ex = assertThrows(DataAccessException.class, () -> jdbc.update(
                    "insert into expense (external_id, user_id, amount, txn_date, created_at, updated_at) "
                            + "values (?, 'user-check', " + amount + ", '2026-01-01', now(6), now(6))",
                    java.util.UUID.randomUUID().toString()), amount);
            assertTrue(NestedExceptionUtils.getMostSpecificCause(ex).getMessage().contains("chk_expense_amount_positive"),
                    "expected the CHECK constraint to fire for " + amount + ": " + ex.getMessage());
        }
        assertEquals(0, expenseRepository.findByUserId("user-check").size());

        // and a positive amount is still accepted
        jdbc.update("insert into expense (external_id, user_id, amount, txn_date, created_at, updated_at) "
                + "values (?, 'user-check', 0.01, '2026-01-01', now(6), now(6))", java.util.UUID.randomUUID().toString());
        assertEquals(1, expenseRepository.findByUserId("user-check").size());
    }

    @Test
    void updatingARowToAZeroAmountThroughJdbcIsAlsoRefused() {
        jdbc.update("insert into expense (external_id, user_id, amount, txn_date, created_at, updated_at) "
                + "values ('ext-upd', 'user-check-upd', 5.00, '2026-01-01', now(6), now(6))");

        assertThrows(DataAccessException.class,
                () -> jdbc.update("update expense set amount = 0 where external_id = 'ext-upd'"));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("update expense set amount = -3 where external_id = 'ext-upd'"));

        assertEquals(0, new BigDecimal("5.00").compareTo(
                jdbc.queryForObject("select amount from expense where external_id = 'ext-upd'", BigDecimal.class)));
    }

    @Test
    void createdExpensePersistsEveryFieldAndReadsBackIdentically() {
        ExpenseDTO in = dto("user-persist-1", "hash-persist-1");

        assertTrue(expenseService.createExpense(in));

        Expense row = expenseRepository.findByUserIdAndExternalId("user-persist-1", in.getExternalId()).orElseThrow();
        assertAll(
                () -> assertNotNull(row.getId()),
                () -> assertEquals(in.getUserId(), row.getUserId(), "user_id"),
                () -> assertEquals(in.getExternalId(), row.getExternalId(), "external_id"),
                () -> assertEquals(in.getSmsHash(), row.getSmsHash(), "sms_hash"),
                () -> assertEquals(in.getSmsReceivedAt(), row.getSmsReceivedAt(), "sms_received_at"),
                () -> assertEquals(new BigDecimal("1234.50"), row.getAmount(), "amount keeps scale 2"),
                () -> assertEquals("INR", row.getCurrency()),
                () -> assertEquals("Blue Tokai Coffee", row.getMerchant()),
                () -> assertEquals("FOOD", row.getCategory()),
                () -> assertEquals("DEBIT", row.getTxnType()),
                () -> assertEquals("4321", row.getAccountLast4()),
                () -> assertEquals(LocalDate.of(2026, 3, 13), row.getTxnDate(), "txn_date"),
                () -> assertEquals(in.getCreatedAt(), row.getCreatedAt(), "created_at"),
                () -> assertNotNull(row.getUpdatedAt(), "updated_at is maintained by the entity")
        );

        // and back out through the service, i.e. the entity -> DTO direction over real data
        ExpenseDTO out = expenseMapper.toDto(expenseRepository.findByUserId("user-persist-1").get(0));
        assertEquals(in, out);
    }

    @Test
    void storedValuesAreUtcAndDecimalNotJvmZoneOrFloat() {
        ExpenseDTO in = dto("user-utc", "hash-utc");
        assertTrue(expenseService.createExpense(in));

        Map<String, Object> raw = jdbc.queryForMap("""
                select date_format(created_at, '%Y-%m-%d %H:%i:%s.%f') as created_at,
                       date_format(sms_received_at, '%Y-%m-%d %H:%i:%s.%f') as sms_received_at,
                       cast(amount as char) as amount, cast(txn_date as char) as txn_date
                from expense where external_id = ?""", in.getExternalId());

        assertAll(
                () -> assertEquals("2026-03-14 09:45:10.654321", raw.get("created_at")),
                () -> assertEquals("2026-03-14 08:15:30.123456", raw.get("sms_received_at")),
                () -> assertEquals("1234.50", raw.get("amount")),
                () -> assertEquals("2026-03-13", raw.get("txn_date"))
        );
    }

    @Test
    void createdAtIsFilledWhenAbsentAndExternalIdIsGenerated() {
        ExpenseDTO in = dto("user-defaults", "hash-defaults");
        in.setCreatedAt(null);
        in.setExternalId(null);
        Instant before = Instant.now().minusSeconds(1);

        assertTrue(expenseService.createExpense(in));

        Expense row = expenseRepository.findByUserId("user-defaults").get(0);
        assertAll(
                () -> assertNotNull(row.getExternalId()),
                () -> assertEquals(36, row.getExternalId().length(), "generated UUID"),
                () -> assertTrue(row.getCreatedAt().isAfter(before)),
                () -> assertTrue(row.getUpdatedAt().isAfter(before))
        );
    }

    @Test
    void databaseColumnDefaultsMatchTheEntityDefaults() {
        jdbc.update("""
                insert into expense (external_id, user_id, amount, txn_date, created_at, updated_at)
                values ('ext-defaults', 'user-sql-defaults', 5.00, '2026-01-01', now(6), now(6))""");

        Expense row = expenseRepository.findByUserId("user-sql-defaults").get(0);

        assertAll(
                () -> assertEquals("INR", row.getCurrency()),
                () -> assertEquals("OTHER", row.getCategory()),
                () -> assertEquals("DEBIT", row.getTxnType()),
                () -> assertNull(row.getSmsHash()),
                () -> assertNull(row.getMerchant())
        );
    }

    @Test
    void secondInsertWithSameUserAndSmsHashViolatesTheUniqueKey() {
        assertTrue(expenseService.createExpense(dto("user-dup", "hash-dup")));

        // the raw repository shows the database constraint, not just the service's "false"
        ExpenseDTO second = dto("user-dup", "hash-dup");
        Expense duplicate = new com.expense.expenseService.Mapper.ExpenseMapper().toEntity(second);
        assertThrows(DataIntegrityViolationException.class, () -> expenseRepository.save(duplicate));

        // the service turns the same collision into a logged rejection
        assertFalse(expenseService.createExpense(dto("user-dup", "hash-dup")));
        assertEquals(1, expenseRepository.findByUserId("user-dup").size());
    }

    @Test
    void sameSmsHashIsAllowedForADifferentUser() {
        assertTrue(expenseService.createExpense(dto("user-a", "hash-shared")));
        assertTrue(expenseService.createExpense(dto("user-b", "hash-shared")));

        assertEquals(1, expenseRepository.findByUserId("user-a").size());
        assertEquals(1, expenseRepository.findByUserId("user-b").size());
    }

    @Test
    void manualExpensesWithoutSmsHashDoNotCollide() {
        assertTrue(expenseService.createExpense(dto("user-manual", null)));
        assertTrue(expenseService.createExpense(dto("user-manual", null)));

        assertEquals(2, expenseRepository.findByUserId("user-manual").size());
    }

    @Test
    void duplicateExternalIdIsRejected() {
        ExpenseDTO first = dto("user-ext-1", "hash-ext-1");
        assertTrue(expenseService.createExpense(first));

        ExpenseDTO clash = dto("user-ext-2", "hash-ext-2");
        clash.setExternalId(first.getExternalId());

        assertFalse(expenseService.createExpense(clash));
        assertTrue(expenseRepository.findByUserId("user-ext-2").isEmpty());
    }

    @Test
    void existsByUserIdAndSmsHashIsScopedToTheUser() {
        assertTrue(expenseService.createExpense(dto("user-exists", "hash-exists")));

        assertTrue(expenseRepository.existsByUserIdAndSmsHash("user-exists", "hash-exists"));
        assertFalse(expenseRepository.existsByUserIdAndSmsHash("someone-else", "hash-exists"));
        assertFalse(expenseRepository.existsByUserIdAndSmsHash("user-exists", "other-hash"));
    }

    @Test
    void listIsNewestTransactionFirstWithIdAsTieBreak() {
        ExpenseDTO oldest = dto("user-order", "h1");
        oldest.setTxnDate(LocalDate.of(2026, 1, 1));
        ExpenseDTO newestFirstInserted = dto("user-order", "h2");
        newestFirstInserted.setTxnDate(LocalDate.of(2026, 3, 1));
        ExpenseDTO newestSecondInserted = dto("user-order", "h3");
        newestSecondInserted.setTxnDate(LocalDate.of(2026, 3, 1));
        assertTrue(expenseService.createExpense(oldest));
        assertTrue(expenseService.createExpense(newestFirstInserted));
        assertTrue(expenseService.createExpense(newestSecondInserted));

        List<String> hashes = expenseRepository.findByUserIdOrderByTxnDateDescIdDesc("user-order").stream()
                .map(Expense::getSmsHash).toList();

        assertEquals(List.of("h3", "h2", "h1"), hashes);
    }

    @Test
    void anExpenseWithoutAUserIdCannotBeStored() {
        ExpenseDTO in = dto(null, "hash-no-user");

        assertFalse(expenseService.createExpense(in));
        assertEquals(0, expenseRepository.count());

        // and the database itself refuses it, independent of the service's check
        Expense bare = new com.expense.expenseService.Mapper.ExpenseMapper().toEntity(dto("x", "y"));
        bare.setUserId(null);
        assertThrows(DataIntegrityViolationException.class, () -> expenseRepository.save(bare));
    }
}
