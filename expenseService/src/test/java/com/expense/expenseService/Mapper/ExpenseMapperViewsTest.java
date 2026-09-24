package com.expense.expenseService.Mapper;

import com.expense.expenseService.DTO.ExpenseItem;
import com.expense.expenseService.DTO.LegacyExpenseItem;
import com.expense.expenseService.Entities.Expense;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The two API views of an entity: what they carry, what they must never carry. */
class ExpenseMapperViewsTest {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final ExpenseMapper mapper = new ExpenseMapper();

    private static Expense entity(Instant smsReceivedAt) {
        Expense e = new Expense();
        e.setId(77L);
        e.setExternalId("11111111-2222-4333-8444-555555555555");
        e.setUserId("66666666-7777-4888-9999-aaaaaaaaaaaa");
        e.setSmsHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        e.setSmsReceivedAt(smsReceivedAt);
        e.setAmount(new BigDecimal("98765.4"));
        e.setCurrency("EUR");
        e.setMerchant("Big Bazaar");
        e.setCategory("GROCERIES");
        e.setTxnType("DEBIT");
        e.setAccountLast4("9876");
        e.setTxnDate(LocalDate.of(2026, 4, 30));
        e.setCreatedAt(Instant.parse("2026-05-01T02:03:04.567891Z"));
        e.setUpdatedAt(Instant.parse("2026-05-02T03:04:05.678912Z"));
        return e;
    }

    @Test
    void toItem_carriesTheNineContractFields() {
        ExpenseItem item = mapper.toItem(entity(Instant.parse("2026-05-01T01:02:03.456789Z")));

        assertAll(
                () -> assertEquals("11111111-2222-4333-8444-555555555555", item.externalId()),
                () -> assertEquals(new BigDecimal("98765.40"), item.amount()),
                () -> assertEquals("EUR", item.currency()),
                () -> assertEquals("Big Bazaar", item.merchant()),
                () -> assertEquals("GROCERIES", item.category()),
                () -> assertEquals("DEBIT", item.txnType()),
                () -> assertEquals("9876", item.accountLast4()),
                () -> assertEquals(LocalDate.of(2026, 4, 30), item.txnDate()),
                () -> assertEquals(Instant.parse("2026-05-01T02:03:04.567891Z"), item.createdAt(), "created_at, not sms time")
        );
    }

    @Test
    void itemJsonHasExactlyTheContractPropertiesAndNeverUserIdOrSmsHash() throws Exception {
        String json = JSON.writeValueAsString(mapper.toItem(entity(Instant.parse("2026-05-01T01:02:03Z"))));
        JsonNode node = JSON.readTree(json);

        assertEquals(Set.of("external_id", "amount", "currency", "merchant", "category", "txn_type",
                "account_last4", "txn_date", "created_at"), fieldNames(node));
        assertTrue(node.get("amount").isNumber());
        assertFalse(json.contains("66666666-7777-4888-9999-aaaaaaaaaaaa"), "user_id value leaked");
        assertFalse(json.contains("0123456789abcdef"), "sms_hash value leaked");
        assertFalse(json.contains("user_id") || json.contains("sms_hash") || json.contains("sms_received_at"));
    }

    @Test
    void itemKeepsExactlyTheNineFieldsSoANewEntityFieldCannotLeakByAccident() {
        assertEquals(9, ExpenseItem.class.getRecordComponents().length);
    }

    @Test
    void legacyItemUsesSmsReceivedAtWhenPresent() {
        LegacyExpenseItem item = mapper.toLegacyItem(entity(Instant.parse("2026-05-01T01:02:03.456789Z")));

        assertEquals(Instant.parse("2026-05-01T01:02:03.456789Z"), item.createdAt());
    }

    @Test
    void legacyItemFallsBackToCreatedAt() {
        LegacyExpenseItem item = mapper.toLegacyItem(entity(null));

        assertEquals(Instant.parse("2026-05-01T02:03:04.567891Z"), item.createdAt());
    }

    @Test
    void legacyItemJsonIsExactlyAmountMerchantCurrencyCreatedAtWithANumericAmount() throws Exception {
        String json = JSON.writeValueAsString(mapper.toLegacyItem(entity(Instant.parse("2026-05-01T01:02:03Z"))));
        JsonNode node = JSON.readTree(json);

        assertEquals(Set.of("amount", "merchant", "currency", "created_at"), fieldNames(node));
        assertTrue(node.get("amount").isNumber(), "the mobile app calls amount.toFixed(2)");
        assertEquals(0, new BigDecimal("98765.40").compareTo(node.get("amount").decimalValue()));
        assertEquals("2026-05-01T01:02:03Z", node.get("created_at").asText());
        assertFalse(json.contains("\"amount\":\""));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
