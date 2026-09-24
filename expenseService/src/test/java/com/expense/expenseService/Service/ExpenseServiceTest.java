package com.expense.expenseService.Service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.expense.expenseService.DTO.CreateExpenseRequest;
import com.expense.expenseService.DTO.ExpenseDTO;
import com.expense.expenseService.DTO.ExpenseItem;
import com.expense.expenseService.DTO.ExpensePage;
import com.expense.expenseService.DTO.LegacyExpenseItem;
import com.expense.expenseService.Entities.Expense;
import com.expense.expenseService.Mapper.ExpenseMapper;
import com.expense.expenseService.Repository.ExpenseRepository;
import com.expense.expenseService.Web.FieldIssue;
import com.expense.expenseService.Web.RequestValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Runs without Docker, a database or Kafka. */
class ExpenseServiceTest {

    private final ExpenseRepository repository = mock(ExpenseRepository.class);
    // 23:30 UTC on the 20th: still the 20th in UTC, already the 21st in Asia/Kolkata (checks the UTC default)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T23:30:00Z"), ZoneOffset.UTC);
    private final ExpenseService service = new ExpenseService(repository, new ExpenseMapper(), CLOCK);

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(ExpenseService.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        serviceLogger.detachAppender(logs);
    }

    private static ExpenseDTO validDto() {
        return ExpenseDTO.builder()
                .externalId("ext-1")
                .userId("user-1")
                .smsHash("hash-1")
                .smsReceivedAt(Instant.parse("2026-03-14T08:15:30Z"))
                .amount(new BigDecimal("1234.50"))
                .currency("INR")
                .merchant("SecretMerchantName")
                .category("FOOD")
                .txnType("DEBIT")
                .accountLast4("4321")
                .txnDate(LocalDate.of(2026, 3, 13))
                .createdAt(Instant.parse("2026-03-14T09:00:00Z"))
                .build();
    }

    @Test
    void createExpense_savesEveryFieldIncludingUserIdExternalIdAndCreatedAt() {
        ExpenseDTO dto = validDto();

        assertTrue(service.createExpense(dto));

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(repository).save(saved.capture());
        Expense e = saved.getValue();
        assertAll(
                () -> assertEquals("user-1", e.getUserId()),
                () -> assertEquals("ext-1", e.getExternalId()),
                () -> assertEquals("hash-1", e.getSmsHash()),
                () -> assertEquals(new BigDecimal("1234.50"), e.getAmount()),
                () -> assertEquals(LocalDate.of(2026, 3, 13), e.getTxnDate()),
                () -> assertEquals(Instant.parse("2026-03-14T09:00:00Z"), e.getCreatedAt())
        );
    }

    @Test
    void createExpense_rejectsMissingUserIdLoudlyAndNeverTouchesTheRepository() {
        ExpenseDTO dto = validDto();
        dto.setUserId(null);

        assertFalse(service.createExpense(dto));

        verify(repository, never()).save(any());
        assertSingleLog(Level.WARN, "user_id is required");
    }

    @Test
    void createExpense_rejectsMissingTxnDate() {
        ExpenseDTO dto = validDto();
        dto.setTxnDate(null);

        assertFalse(service.createExpense(dto));

        verify(repository, never()).save(any());
        assertSingleLog(Level.WARN, "txn_date is required");
    }

    @Test
    void createExpense_rejectsNonPositiveAmount() {
        ExpenseDTO dto = validDto();
        dto.setAmount(new BigDecimal("-3"));

        assertFalse(service.createExpense(dto));

        verify(repository, never()).save(any());
        assertSingleLog(Level.WARN, "amount must be greater than zero");
    }

    @Test
    void createExpense_returnsFalseAndLogsWhenTheDatabaseRejectsARow() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup",
                new RuntimeException("Duplicate entry 'user-1-hash-1' for key 'expense.uq_expense_user_sms'")));

        assertFalse(service.createExpense(validDto()));

        assertSingleLog(Level.WARN, "Duplicate entry");
    }

    @Test
    void createExpense_logsUnexpectedFailuresAtErrorWithTheStackTrace() {
        when(repository.save(any())).thenThrow(new IllegalStateException("boom"));

        assertFalse(service.createExpense(validDto()));

        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        assertNotNull(event.getThrowableProxy(), "the exception itself must be logged, not just its message");
    }

    @Test
    void logsNeverContainAmountOrMerchant() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        service.createExpense(validDto());

        String all = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertAll(
                () -> assertTrue(all.contains("user-1"), "ids are logged"),
                () -> assertFalse(all.contains("SecretMerchantName")),
                () -> assertFalse(all.contains("1234"))
        );
    }

    @Test
    void createExpense_returnsFalseForNullPayload() {
        assertFalse(service.createExpense(null));
        verifyNoInteractions(repository);
    }

    @Test
    void updateExpense_changesOnlyProvidedFields() {
        Expense existing = new ExpenseMapper().toEntity(validDto());
        when(repository.findByUserIdAndExternalId("user-1", "ext-1")).thenReturn(Optional.of(existing));
        ExpenseDTO patch = ExpenseDTO.builder().userId("user-1").externalId("ext-1")
                .amount(new BigDecimal("50")).build();

        assertTrue(service.updateExpense(patch));

        assertEquals(new BigDecimal("50.00"), existing.getAmount());
        assertEquals("SecretMerchantName", existing.getMerchant(), "blank merchant in the patch keeps the old one");
        assertEquals("INR", existing.getCurrency());
        verify(repository).save(existing);
    }

    @Test
    void updateExpense_returnsFalseWhenNotFound() {
        when(repository.findByUserIdAndExternalId(any(), any())).thenReturn(Optional.empty());

        assertFalse(service.updateExpense(validDto()));

        verify(repository, never()).save(any());
    }

    @Test
    void updateExpense_isNullSafeAndLogsLikeCreate() {
        assertFalse(service.updateExpense(null));

        verifyNoInteractions(repository);
        assertSingleLog(Level.WARN, "no payload");
    }

    @Test
    void updateExpense_requiresUserIdAndExternalId() {
        ExpenseDTO noUser = validDto();
        noUser.setUserId(null);
        ExpenseDTO noExternal = validDto();
        noExternal.setExternalId(" ");

        assertFalse(service.updateExpense(noUser));
        assertFalse(service.updateExpense(noExternal));

        verifyNoInteractions(repository);
        assertEquals(2, logs.list.size());
        assertTrue(logs.list.stream().allMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    void updateExpense_returnsFalseInsteadOfThrowingForAnInvalidAmount() {
        Expense existing = new ExpenseMapper().toEntity(validDto());
        when(repository.findByUserIdAndExternalId("user-1", "ext-1")).thenReturn(Optional.of(existing));
        ExpenseDTO patch = ExpenseDTO.builder().userId("user-1").externalId("ext-1")
                .amount(new BigDecimal("1e999999999")).build();

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertFalse(service.updateExpense(patch)));

        verify(repository, never()).save(any());
        assertEquals(new BigDecimal("1234.50"), existing.getAmount(), "a rejected patch must not change the row");
        assertSingleLog(Level.WARN, "exceeds the supported range");
    }

    @Test
    void updateExpense_returnsFalseAndLogsWhenTheDatabaseRejectsTheUpdate() {
        Expense existing = new ExpenseMapper().toEntity(validDto());
        when(repository.findByUserIdAndExternalId("user-1", "ext-1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("x", new RuntimeException("check violated")));

        assertFalse(service.updateExpense(validDto()));

        assertSingleLog(Level.WARN, "check violated");
    }

    @Test
    void updateExpense_logsUnexpectedFailuresAtErrorAndReturnsFalse() {
        when(repository.findByUserIdAndExternalId(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertFalse(service.updateExpense(validDto()));

        assertEquals(1, logs.list.size());
        assertEquals(Level.ERROR, logs.list.get(0).getLevel());
        assertNotNull(logs.list.get(0).getThrowableProxy());
    }

    // ---- list / legacy alias ------------------------------------------------------------------------------

    @Test
    void list_queriesOnlyTheCallersRowsWithTheContractOrderAndMapsThemToItems() {
        Expense e = new ExpenseMapper().toEntity(validDto());
        Pageable[] seen = new Pageable[1];
        when(repository.search(eq("user-1"), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 1, 31)), any()))
                .thenAnswer(inv -> {
                    seen[0] = inv.getArgument(3);
                    return new PageImpl<>(List.of(e), seen[0], 41);
                });

        ExpensePage page = service.list("user-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 1, 20);

        assertAll(
                () -> assertEquals(1, seen[0].getPageNumber()),
                () -> assertEquals(20, seen[0].getPageSize()),
                () -> assertEquals("txnDate: DESC,id: DESC", seen[0].getSort().toString()),
                () -> assertEquals(1, page.page()),
                () -> assertEquals(20, page.size()),
                () -> assertEquals(41, page.totalElements()),
                () -> assertEquals(3, page.totalPages()),
                () -> assertEquals(1, page.items().size()),
                () -> assertEquals("ext-1", page.items().get(0).externalId())
        );
    }

    @Test
    void list_rejectsBadPagingAndDatesWithEveryProblemListed() {
        RequestValidationException ex = assertThrows(RequestValidationException.class,
                () -> service.list("user-1", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), -1, 201));

        Map<String, String> byField = ex.getIssues().stream()
                .collect(Collectors.toMap(FieldIssue::field, FieldIssue::message, (a, b) -> a + "; " + b));
        assertAll(
                () -> assertTrue(byField.containsKey("page")),
                () -> assertEquals("must be between 1 and 200", byField.get("size")),
                () -> assertTrue(byField.containsKey("from"))
        );
        verifyNoInteractions(repository);
    }

    @Test
    void list_acceptsTheLimitsOfTheContract() {
        when(repository.search(any(), any(), any(), any())).thenReturn(Page.empty());

        assertDoesNotThrow(() -> service.list("user-1", null, null, 0, 1));
        assertDoesNotThrow(() -> service.list("user-1", null, null, 0, 200));
        assertDoesNotThrow(() -> service.list("user-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), 0, 20));
    }

    @Test
    void list_rejectsAPageWhoseRowOffsetWouldOverflowAnInt() {
        RequestValidationException ex = assertThrows(RequestValidationException.class,
                () -> service.list("user-1", null, null, Integer.MAX_VALUE, 20));

        assertEquals("page", ex.getIssues().get(0).field());
        verifyNoInteractions(repository);
        // the largest offset that still fits is served
        when(repository.search(any(), any(), any(), any())).thenReturn(Page.empty());
        assertDoesNotThrow(() -> service.list("user-1", null, null, 10_737_418, 200));
    }

    @Test
    void list_rejectsDatesOutsideTheSupportedWindow() {
        assertThrows(RequestValidationException.class, () -> service.list("user-1", LocalDate.of(1969, 12, 31), null, 0, 20));
        assertThrows(RequestValidationException.class, () -> service.list("user-1", null, LocalDate.of(10000, 1, 1), 0, 20));
    }

    @Test
    void legacyList_usesCoalesceOfSmsReceivedAtAndCreatedAt() {
        Expense fromSms = new ExpenseMapper().toEntity(validDto());       // has sms_received_at
        Expense manual = new ExpenseMapper().toEntity(validDto());
        manual.setSmsReceivedAt(null);                                     // falls back to created_at
        when(repository.findTop500ByUserIdOrderByTxnDateDescIdDesc("user-1")).thenReturn(List.of(fromSms, manual));

        List<LegacyExpenseItem> rows = service.legacyList("user-1");

        assertEquals(Instant.parse("2026-03-14T08:15:30Z"), rows.get(0).createdAt());
        assertEquals(Instant.parse("2026-03-14T09:00:00Z"), rows.get(1).createdAt());
        assertEquals(new BigDecimal("1234.50"), rows.get(0).amount());
    }

    // ---- create ---------------------------------------------------------------------------------------------

    private static CreateExpenseRequest request(String amount) {
        return new CreateExpenseRequest(new BigDecimal(amount), null, null, null, null, null);
    }

    private Expense savedByCreate() {
        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void create_appliesTheDocumentedDefaultsAndUsesTheCallersUserId() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExpenseItem item = service.create("user-1", request("12.345"));

        Expense e = savedByCreate();
        assertAll(
                () -> assertEquals("user-1", e.getUserId()),
                () -> assertEquals(new BigDecimal("12.35"), e.getAmount()),
                () -> assertEquals("INR", e.getCurrency()),
                () -> assertEquals("OTHER", e.getCategory()),
                () -> assertEquals("DEBIT", e.getTxnType()),
                () -> assertEquals(LocalDate.of(2026, 9, 20), e.getTxnDate(), "today in UTC, not the JVM zone"),
                () -> assertNull(e.getMerchant()),
                () -> assertNull(e.getSmsHash()),
                () -> assertNull(e.getSmsReceivedAt()),
                () -> assertEquals(new BigDecimal("12.35"), item.amount())
        );
    }

    @Test
    void create_keepsTheGivenValuesAndTrimsTheMerchant() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CreateExpenseRequest in = new CreateExpenseRequest(new BigDecimal("99.99"), "USD", "  Blue Tokai  ",
                "FOOD", "CREDIT", LocalDate.of(2026, 1, 2));

        service.create("user-1", in);

        Expense e = savedByCreate();
        assertAll(
                () -> assertEquals("USD", e.getCurrency()),
                () -> assertEquals("Blue Tokai", e.getMerchant()),
                () -> assertEquals("FOOD", e.getCategory()),
                () -> assertEquals("CREDIT", e.getTxnType()),
                () -> assertEquals(LocalDate.of(2026, 1, 2), e.getTxnDate())
        );
    }

    @Test
    void create_blankMerchantIsStoredAsNull() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create("user-1", new CreateExpenseRequest(new BigDecimal("1"), null, "   ", null, null, null));

        assertNull(savedByCreate().getMerchant());
    }

    @Test
    void create_listsEveryInvalidField() {
        CreateExpenseRequest bad = new CreateExpenseRequest(null, "inr", "x".repeat(256), "food", "REFUND",
                LocalDate.of(1900, 1, 1));

        RequestValidationException ex = assertThrows(RequestValidationException.class, () -> service.create("user-1", bad));

        Map<String, String> byField = ex.getIssues().stream()
                .collect(Collectors.toMap(FieldIssue::field, FieldIssue::message));
        assertEquals(java.util.Set.of("amount", "currency", "merchant", "category", "txn_type", "txn_date"), byField.keySet());
        assertTrue(byField.get("category").contains("CASH_WITHDRAWAL"));
        assertTrue(byField.get("txn_type").contains("DEBIT, CREDIT, OTHER"));
        verify(repository, never()).save(any());
    }

    @Test
    void create_rejectsInvalidCurrencyCodes() {
        for (String bad : new String[]{"", "IN", "INRR", "inr", "Inr", "ZZZ", "12A", "€€€", " INR", "INR "}) {
            CreateExpenseRequest in = new CreateExpenseRequest(new BigDecimal("1"), bad, null, null, null, null);
            assertThrows(RequestValidationException.class, () -> service.create("user-1", in), "[" + bad + "]");
        }
        for (String good : new String[]{"INR", "USD", "EUR", "JPY", "GBP"}) {
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            assertDoesNotThrow(() -> service.create("user-1",
                    new CreateExpenseRequest(new BigDecimal("1"), good, null, null, null, null)), good);
        }
    }

    @Test
    void create_acceptsEveryPinnedCategoryAndTxnType() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        for (String category : new String[]{"FOOD", "GROCERIES", "TRANSPORT", "SHOPPING", "BILLS_UTILITIES",
                "ENTERTAINMENT", "HEALTH", "TRAVEL", "EDUCATION", "RENT", "EMI_LOANS", "INVESTMENT", "TRANSFER",
                "CASH_WITHDRAWAL", "OTHER"}) {
            assertDoesNotThrow(() -> service.create("user-1",
                    new CreateExpenseRequest(new BigDecimal("1"), null, null, category, null, null)), category);
        }
        for (String type : new String[]{"DEBIT", "CREDIT", "OTHER"}) {
            assertDoesNotThrow(() -> service.create("user-1",
                    new CreateExpenseRequest(new BigDecimal("1"), null, null, null, type, null)), type);
        }
        assertEquals(15, com.expense.expenseService.Entities.ExpenseCategory.values().length);
    }

    @Test
    void create_rejectsAnAbsurdAmountThatBypassedJacksonWithoutExpandingIt() {
        CreateExpenseRequest in = new CreateExpenseRequest(new BigDecimal("1e999999999"), null, null, null, null, null);

        RequestValidationException ex = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThrows(RequestValidationException.class, () -> service.create("user-1", in)));

        assertEquals("amount", ex.getIssues().get(0).field());
        verify(repository, never()).save(any());
    }

    @Test
    void create_rejectsANullRequest() {
        assertThrows(RequestValidationException.class, () -> service.create("user-1", null));
    }

    @Test
    void create_letsDatabaseFailuresPropagateSoTheApiAnswers500NotFalse201() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("x"));

        assertThrows(DataIntegrityViolationException.class, () -> service.create("user-1", request("5")));
    }

    private void assertSingleLog(Level level, String fragment) {
        assertEquals(1, logs.list.size(), "expected exactly one log line, got " + logs.list);
        ILoggingEvent event = logs.list.get(0);
        assertEquals(level, event.getLevel());
        assertTrue(event.getFormattedMessage().contains(fragment),
                "log '" + event.getFormattedMessage() + "' should contain '" + fragment + "'");
    }
}
