package com.expense.expenseService.Mapper;

import com.expense.expenseService.DTO.ExpenseDTO;
import com.expense.expenseService.Entities.Expense;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the bug where a reflective convertValue between the snake_case DTO and the
 * camelCase entity silently dropped user_id, external_id and created_at.
 * Every field carries a DISTINCT non-null value so a swapped or dropped field cannot go unnoticed.
 */
class ExpenseMapperTest {

    /** Entity fields owned by persistence: they have no DTO counterpart and are never mapped. */
    private static final Set<String> PERSISTENCE_MANAGED = Set.of("id", "updatedAt");

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final ExpenseMapper mapper = new ExpenseMapper();

    private static ExpenseDTO fullDto() {
        return ExpenseDTO.builder()
                .externalId("3f2b8c1e-5d7a-4e9b-9c1d-0a1b2c3d4e5f")
                .userId("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")
                .smsHash("0e36872bae1633bb10cabaea875d767b54e27b56a8cf794d9b6d094cc820a256")
                .smsReceivedAt(Instant.parse("2026-03-14T08:15:30.123456Z"))
                .amount(new BigDecimal("1234.50"))
                .currency("USD")
                .merchant("Blue Tokai Coffee")
                .category("FOOD")
                .txnType("CREDIT")
                .accountLast4("4321")
                .txnDate(LocalDate.of(2026, 3, 13))
                .createdAt(Instant.parse("2026-03-14T09:45:10.654321Z"))
                .build();
    }

    private static Expense fullEntity() {
        Expense e = new Expense();
        e.setId(77L);
        e.setExternalId("11111111-2222-4333-8444-555555555555");
        e.setUserId("66666666-7777-4888-9999-aaaaaaaaaaaa");
        e.setSmsHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        e.setSmsReceivedAt(Instant.parse("2026-05-01T01:02:03.456789Z"));
        e.setAmount(new BigDecimal("98765.43"));
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
    void dtoToEntity_keepsEveryField() {
        ExpenseDTO dto = fullDto();

        Expense entity = mapper.toEntity(dto);

        assertAll(
                () -> assertEquals(dto.getExternalId(), entity.getExternalId(), "external_id"),
                () -> assertEquals(dto.getUserId(), entity.getUserId(), "user_id"),
                () -> assertEquals(dto.getSmsHash(), entity.getSmsHash(), "sms_hash"),
                () -> assertEquals(dto.getSmsReceivedAt(), entity.getSmsReceivedAt(), "sms_received_at"),
                () -> assertEquals(dto.getAmount(), entity.getAmount(), "amount"),
                () -> assertEquals(dto.getCurrency(), entity.getCurrency(), "currency"),
                () -> assertEquals(dto.getMerchant(), entity.getMerchant(), "merchant"),
                () -> assertEquals(dto.getCategory(), entity.getCategory(), "category"),
                () -> assertEquals(dto.getTxnType(), entity.getTxnType(), "txn_type"),
                () -> assertEquals(dto.getAccountLast4(), entity.getAccountLast4(), "account_last4"),
                () -> assertEquals(dto.getTxnDate(), entity.getTxnDate(), "txn_date"),
                () -> assertEquals(dto.getCreatedAt(), entity.getCreatedAt(), "created_at"),
                // a field added later to the entity but forgotten in the mapper shows up here
                () -> assertEquals(List.of(), nullFields(entity, PERSISTENCE_MANAGED),
                        "entity fields left null by the mapper")
        );
    }

    @Test
    void entityToDto_keepsEveryField() {
        Expense entity = fullEntity();

        ExpenseDTO dto = mapper.toDto(entity);

        assertAll(
                () -> assertEquals(entity.getExternalId(), dto.getExternalId(), "external_id"),
                () -> assertEquals(entity.getUserId(), dto.getUserId(), "user_id"),
                () -> assertEquals(entity.getSmsHash(), dto.getSmsHash(), "sms_hash"),
                () -> assertEquals(entity.getSmsReceivedAt(), dto.getSmsReceivedAt(), "sms_received_at"),
                () -> assertEquals(entity.getAmount(), dto.getAmount(), "amount"),
                () -> assertEquals(entity.getCurrency(), dto.getCurrency(), "currency"),
                () -> assertEquals(entity.getMerchant(), dto.getMerchant(), "merchant"),
                () -> assertEquals(entity.getCategory(), dto.getCategory(), "category"),
                () -> assertEquals(entity.getTxnType(), dto.getTxnType(), "txn_type"),
                () -> assertEquals(entity.getAccountLast4(), dto.getAccountLast4(), "account_last4"),
                () -> assertEquals(entity.getTxnDate(), dto.getTxnDate(), "txn_date"),
                () -> assertEquals(entity.getCreatedAt(), dto.getCreatedAt(), "created_at"),
                () -> assertEquals(List.of(), nullFields(dto, Set.of()), "DTO fields left null by the mapper")
        );
    }

    @Test
    void roundTrip_dtoToEntityToDto_isLossless() {
        ExpenseDTO original = fullDto();

        ExpenseDTO roundTripped = mapper.toDto(mapper.toEntity(original));

        assertEquals(original, roundTripped);
    }

    @Test
    void fixtureValuesAreDistinct_soASwappedFieldCannotHide() {
        // guards the guard: two fields sharing a value would let a swap between them pass unnoticed
        List<Object> values = new ArrayList<>();
        for (Field f : instanceFields(ExpenseDTO.class)) {
            values.add(read(f, fullDto()));
        }
        assertEquals(values.size(), Set.copyOf(values).size(), "every DTO field needs a distinct value");
        assertEquals(12, values.size(), "DTO gained or lost a field: update this test and the mapper");
    }

    @Test
    void toEntity_blankOptionalDefaultsFallBackToEntityDefaults() {
        ExpenseDTO dto = fullDto();
        dto.setCurrency(" ");
        dto.setCategory(null);
        dto.setTxnType("");

        Expense entity = mapper.toEntity(dto);

        assertAll(
                () -> assertEquals("INR", entity.getCurrency()),
                () -> assertEquals("OTHER", entity.getCategory()),
                () -> assertEquals("DEBIT", entity.getTxnType())
        );
    }

    @Test
    void toEntity_rejectsMissingOrNonPositiveAmount() {
        ExpenseDTO noAmount = fullDto();
        noAmount.setAmount(null);
        ExpenseDTO zero = fullDto();
        zero.setAmount(new BigDecimal("0.00"));
        ExpenseDTO negative = fullDto();
        negative.setAmount(new BigDecimal("-5"));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> mapper.toEntity(noAmount)),
                () -> assertThrows(IllegalArgumentException.class, () -> mapper.toEntity(zero)),
                () -> assertThrows(IllegalArgumentException.class, () -> mapper.toEntity(negative))
        );
    }

    @Test
    void dtoSerializesToSnakeCaseWithNumericAmount() throws Exception {
        String json = JSON.writeValueAsString(fullDto());
        JsonNode node = JSON.readTree(json);

        assertAll(
                () -> assertEquals(12, node.size(), "unexpected property set: " + node.fieldNames()),
                () -> assertEquals("3f2b8c1e-5d7a-4e9b-9c1d-0a1b2c3d4e5f", node.get("external_id").asText()),
                () -> assertEquals("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", node.get("user_id").asText()),
                () -> assertTrue(node.get("sms_hash").isTextual()),
                () -> assertEquals("2026-03-14T08:15:30.123456Z", node.get("sms_received_at").asText()),
                () -> assertTrue(node.get("amount").isNumber(), "amount must be a JSON number, was " + node.get("amount")),
                () -> assertEquals(0, new BigDecimal("1234.50").compareTo(node.get("amount").decimalValue())),
                () -> assertEquals("USD", node.get("currency").asText()),
                () -> assertEquals("Blue Tokai Coffee", node.get("merchant").asText()),
                () -> assertEquals("FOOD", node.get("category").asText()),
                () -> assertEquals("CREDIT", node.get("txn_type").asText()),
                () -> assertEquals("4321", node.get("account_last4").asText()),
                () -> assertEquals("2026-03-13", node.get("txn_date").asText()),
                () -> assertEquals("2026-03-14T09:45:10.654321Z", node.get("created_at").asText()),
                () -> assertFalse(json.contains("\"amount\":\""), "amount must not be quoted")
        );
    }

    @Test
    void dtoRoundTripsThroughJackson() throws Exception {
        ExpenseDTO original = fullDto();

        ExpenseDTO parsed = JSON.readValue(JSON.writeValueAsString(original), ExpenseDTO.class);

        assertEquals(original, parsed);
    }

    @Test
    void dtoParsesSnakeCaseNumberAndIgnoresUnknownProperties() throws Exception {
        String json = """
                {"external_id":"e-1","user_id":"u-1","amount":1234.5,"txn_date":"2026-01-02",
                 "some_future_field":"ignored"}
                """;

        ExpenseDTO dto = JSON.readValue(json, ExpenseDTO.class);

        assertAll(
                () -> assertEquals("e-1", dto.getExternalId()),
                () -> assertEquals("u-1", dto.getUserId()),
                () -> assertEquals(0, new BigDecimal("1234.5").compareTo(dto.getAmount())),
                () -> assertEquals(LocalDate.of(2026, 1, 2), dto.getTxnDate())
        );
    }

    private static List<String> nullFields(Object target, Set<String> skip) {
        List<String> nulls = new ArrayList<>();
        for (Field f : instanceFields(target.getClass())) {
            if (!skip.contains(f.getName()) && read(f, target) == null) {
                nulls.add(f.getName());
            }
        }
        return nulls;
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                fields.add(f);
            }
        }
        return fields;
    }

    private static Object read(Field f, Object target) {
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }
}
