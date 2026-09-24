package com.expense.expenseService.Controller;

import com.expense.expenseService.Entities.Expense;
import com.expense.expenseService.Mapper.ExpenseMapper;
import com.expense.expenseService.Repository.ExpenseRepository;
import com.expense.expenseService.Service.ExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MVC slice: the real identity filter, argument resolver, controller, service, mapper and error advice, with the
 * repository mocked (no database, no Docker). The database behaviour is covered by ExpenseApiIntegrationTest.
 */
@WebMvcTest(ExpenseController.class)
@Import({ExpenseService.class, ExpenseMapper.class})
class ExpenseControllerTest {

    private static final String USER = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
    private static final String OTHER = "11111111-2222-4333-8444-555555555555";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ExpenseRepository repository;

    @BeforeEach
    void repositoryEchoesSavedRowsLikeJpaWould() {
        when(repository.save(any())).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            // what @PrePersist does inside the real persistence layer
            e.setExternalId(UUID.randomUUID().toString());
            e.setCreatedAt(Instant.parse("2026-09-20T10:00:00.123456Z"));
            return e;
        });
    }

    private static Expense row(String smsHash, Instant smsReceivedAt, String amount, String merchant) {
        Expense e = new Expense();
        e.setExternalId(UUID.randomUUID().toString());
        e.setUserId(USER);
        e.setSmsHash(smsHash);
        e.setSmsReceivedAt(smsReceivedAt);
        e.setAmount(new BigDecimal(amount));
        e.setCurrency("INR");
        e.setMerchant(merchant);
        e.setCategory("FOOD");
        e.setTxnType("DEBIT");
        e.setAccountLast4("4321");
        e.setTxnDate(LocalDate.of(2026, 3, 13));
        e.setCreatedAt(Instant.parse("2026-03-14T09:45:10.654321Z"));
        return e;
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private static MockHttpServletRequestBuilder listAs(String userId, String query) {
        return get("/expense/v1/expenses" + query).header("X-User-Id", userId);
    }

    private static MockHttpServletRequestBuilder postAs(String userId, String json) {
        return post("/expense/v1/expenses").header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    // ---- identity -----------------------------------------------------------------------------------------

    @Test
    void everyEndpointRequiresAnIdentity() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                get("/expense/v1/expenses"),
                get("/expense/v1/getExpense"),
                post("/expense/v1/expenses").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))) {
            perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.status").value(401));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void aDirectRequestForErrorWithoutAnIdentityIsThePinned401() throws Exception {
        perform(get("/error"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/error"))
                .andExpect(jsonPath("$.length()").value(6));
        perform(post("/error").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(repository);
    }

    @Test
    void aMalformedIdentityNeverReachesTheRepository() throws Exception {
        perform(get("/expense/v1/expenses").header("X-User-Id", "1' or '1'='1")).andExpect(status().isUnauthorized());
        perform(get("/expense/v1/getExpense").header("X-User-Id", "not-a-uuid")).andExpect(status().isUnauthorized());

        verifyNoInteractions(repository);
    }

    @Test
    void theOldUnprefixedEndpointsAreGone() throws Exception {
        perform(get("/getExpense?user_id=" + OTHER).header("X-User-Id", USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        perform(post("/addExpense").header("X-User-Id", USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":5,\"user_id\":\"" + OTHER + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(repository);
    }

    // ---- list ---------------------------------------------------------------------------------------------

    @Test
    void listScopesTheQueryToTheHeaderUserAndIgnoresAnyUserIdInTheQueryString() throws Exception {
        when(repository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        perform(listAs(USER, "?user_id=" + OTHER + "&userId=" + OTHER + "&X-User-Id=" + OTHER)).andExpect(status().isOk());

        verify(repository).search(eq(USER), isNull(), isNull(), any());
        verify(repository, never()).search(eq(OTHER), any(), any(), any());
    }

    @Test
    void listUppercaseHeaderIsNormalisedToTheLowercaseOwnerId() throws Exception {
        when(repository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        perform(listAs(USER.toUpperCase(), "")).andExpect(status().isOk());

        verify(repository).search(eq(USER), any(), any(), any());
    }

    @Test
    void listReturnsThePagedShapeWithNumericAmountsAndWithoutUserIdOrSmsHash() throws Exception {
        Expense a = row("secret-hash-a", Instant.parse("2026-03-14T08:15:30Z"), "1234.5", "Blue Tokai");
        Expense b = row(null, null, "20", null);
        when(repository.search(any(), any(), any(), any())).thenAnswer(inv ->
                new PageImpl<>(List.of(a, b), inv.<Pageable>getArgument(3), 45));

        String body = perform(listAs(USER, "?page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total_elements").value(45))
                .andExpect(jsonPath("$.total_pages").value(3))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.items[0].external_id").value(a.getExternalId()))
                .andExpect(jsonPath("$.items[0].amount").value(1234.5))
                .andExpect(jsonPath("$.items[0].currency").value("INR"))
                .andExpect(jsonPath("$.items[0].merchant").value("Blue Tokai"))
                .andExpect(jsonPath("$.items[0].category").value("FOOD"))
                .andExpect(jsonPath("$.items[0].txn_type").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].account_last4").value("4321"))
                .andExpect(jsonPath("$.items[0].txn_date").value("2026-03-13"))
                .andExpect(jsonPath("$.items[0].created_at").value("2026-03-14T09:45:10.654321Z"))
                .andExpect(jsonPath("$.items[0].length()").value(9))
                .andExpect(jsonPath("$.items[1].merchant").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"amount\":1234.50"), "amount must be a JSON number: " + body);
        assertFalse(body.contains("user_id"));
        assertFalse(body.contains("sms_hash"));
        assertFalse(body.contains("secret-hash-a"));
        assertFalse(body.contains(USER));
    }

    @Test
    void listDefaultsToPageZeroSizeTwentyNewestTransactionFirst() throws Exception {
        when(repository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        perform(listAs(USER, "")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(eq(USER), isNull(), isNull(), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals("txnDate: DESC,id: DESC", pageable.getValue().getSort().toString());
    }

    @Test
    void listPassesInclusiveIsoDatesThrough() throws Exception {
        when(repository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        perform(listAs(USER, "?from=2026-01-01&to=2026-01-31")).andExpect(status().isOk());

        verify(repository).search(eq(USER), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 1, 31)), any());
    }

    @Test
    void listAcceptsFromEqualToAndEitherBoundAlone() throws Exception {
        when(repository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        perform(listAs(USER, "?from=2026-01-05&to=2026-01-05")).andExpect(status().isOk());
        perform(listAs(USER, "?from=2026-01-05")).andExpect(status().isOk());
        perform(listAs(USER, "?to=2026-01-05")).andExpect(status().isOk());
    }

    @Test
    void listAcceptsSizeUpToTwoHundred() throws Exception {
        when(repository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        perform(listAs(USER, "?size=200")).andExpect(status().isOk());
        perform(listAs(USER, "?size=1")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({
            "?size=201,                    size",
            "?size=100000,                 size",
            "?size=0,                      size",
            "?size=-5,                     size",
            "?page=-1,                     page",
            "?page=2147483647,             page",
            "?page=10737419&size=200,      page",
            "?from=2026-02-01&to=2026-01-01, from",
            "?from=1969-12-31,             from",
            "?to=1969-12-31,               to"
    })
    void listRejectsOutOfRangeParametersWithValidationFailedAndDetails(String query, String field) throws Exception {
        perform(listAs(USER, query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/expense/v1/expenses"))
                .andExpect(jsonPath("$.details[*].field", hasItem(field)));

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=abc", "?size=ten", "?page=99999999999", "?from=2026-13-45", "?to=yesterday",
            "?from=01/02/2026", "?from=2026-1-1"})
    void listRejectsUnparseableParametersWithBadRequest(String query) throws Exception {
        perform(listAs(USER, query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.details").doesNotExist());

        verifyNoInteractions(repository);
    }

    // ---- legacy alias -------------------------------------------------------------------------------------

    @Test
    void aliasReturnsABareArrayWithNumericAmountAndCoalescedCreatedAt() throws Exception {
        Expense fromSms = row("h1", Instant.parse("2026-03-14T08:15:30Z"), "1234.5", "Blue Tokai");
        Expense manual = row(null, null, "20", "Cash");
        when(repository.findTop500ByUserIdOrderByTxnDateDescIdDesc(USER)).thenReturn(List.of(fromSms, manual));

        String body = perform(get("/expense/v1/getExpense").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].amount").isNumber())
                .andExpect(jsonPath("$[0].amount").value(1234.5))
                .andExpect(jsonPath("$[0].merchant").value("Blue Tokai"))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].created_at").value("2026-03-14T08:15:30Z"))    // sms_received_at wins
                .andExpect(jsonPath("$[1].amount").value(20.0))
                .andExpect(jsonPath("$[1].created_at").value("2026-03-14T09:45:10.654321Z")) // falls back to created_at
                .andExpect(jsonPath("$[0].length()").value(4))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.startsWith("[") && body.endsWith("]"), "a bare array, no wrapper: " + body);
        assertFalse(body.contains("user_id") || body.contains("sms_hash") || body.contains("external_id"));
    }

    @Test
    void aliasReturnsAnEmptyArrayNotAnErrorForANewUser() throws Exception {
        when(repository.findTop500ByUserIdOrderByTxnDateDescIdDesc(USER)).thenReturn(List.of());

        perform(get("/expense/v1/getExpense").header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(content().string("[]"));
    }

    @Test
    void aliasIgnoresAUserIdQueryParameter() throws Exception {
        when(repository.findTop500ByUserIdOrderByTxnDateDescIdDesc(any())).thenReturn(List.of());

        perform(get("/expense/v1/getExpense?user_id=" + OTHER).header("X-User-Id", USER)).andExpect(status().isOk());

        verify(repository).findTop500ByUserIdOrderByTxnDateDescIdDesc(USER);
        verify(repository, never()).findTop500ByUserIdOrderByTxnDateDescIdDesc(OTHER);
    }

    // ---- create -------------------------------------------------------------------------------------------

    @Test
    void postCreatesTheExpenseForTheHeaderUserAnd201WithTheItem() throws Exception {
        String body = perform(postAs(USER, """
                {"amount": 249.5, "currency": "USD", "merchant": "  Blue Tokai ", "category": "FOOD",
                 "txn_type": "DEBIT", "txn_date": "2026-03-13"}"""))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.external_id", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.amount").value(249.5))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.merchant").value("Blue Tokai"))
                .andExpect(jsonPath("$.category").value("FOOD"))
                .andExpect(jsonPath("$.txn_type").value("DEBIT"))
                .andExpect(jsonPath("$.account_last4").value(nullValue()))
                .andExpect(jsonPath("$.txn_date").value("2026-03-13"))
                .andExpect(jsonPath("$.created_at").value("2026-09-20T10:00:00.123456Z"))
                .andExpect(jsonPath("$.length()").value(9))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"amount\":249.50"), body);
        assertFalse(body.contains("user_id") || body.contains("sms_hash"));

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(repository).save(saved.capture());
        assertEquals(USER, saved.getValue().getUserId());
    }

    @Test
    void postAppliesTheDefaults() throws Exception {
        perform(postAs(USER, "{\"amount\": 10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(10.0))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.category").value("OTHER"))
                .andExpect(jsonPath("$.txn_type").value("DEBIT"))
                .andExpect(jsonPath("$.merchant").value(nullValue()));

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(repository).save(saved.capture());
        LocalDate before = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        assertTrue(!saved.getValue().getTxnDate().isBefore(before)
                && !saved.getValue().getTxnDate().isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1)),
                "txn_date defaults to today (UTC), was " + saved.getValue().getTxnDate());
    }

    @Test
    void postRoundsToTwoDecimalsHalfUpAndAcceptsANumericString() throws Exception {
        perform(postAs(USER, "{\"amount\": 12.345}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(12.35));
        perform(postAs(USER, "{\"amount\": \"12.5\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(12.5));
        perform(postAs(USER, "{\"amount\": 99999999999999999.99}")).andExpect(status().isCreated());
    }

    @Test
    void aForgedUserIdOrAnyOtherServerOwnedFieldInTheBodyIsIgnored() throws Exception {
        String forgedExternalId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        String body = perform(postAs(USER, """
                {"amount": 5, "user_id": "%s", "userId": "%s", "external_id": "%s", "externalId": "%s",
                 "created_at": "1999-01-01T00:00:00Z", "createdAt": "1999-01-01T00:00:00Z", "updated_at": "1999-01-01T00:00:00Z",
                 "sms_hash": "forged-hash", "smsHash": "forged-hash", "sms_received_at": "1999-01-01T00:00:00Z",
                 "id": 42, "account_last4": "0000", "is_admin": true}""".formatted(OTHER, OTHER, forgedExternalId, forgedExternalId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(repository).save(saved.capture());
        Expense e = saved.getValue();
        assertAll(
                () -> assertEquals(USER, e.getUserId(), "the owner is the header user, never the body's"),
                () -> assertNull(e.getSmsHash()),
                () -> assertNull(e.getSmsReceivedAt()),
                () -> assertNull(e.getAccountLast4()),
                () -> assertNull(e.getId()),
                () -> assertNotEquals(forgedExternalId, e.getExternalId()),
                () -> assertNotEquals(Instant.parse("1999-01-01T00:00:00Z"), e.getCreatedAt()),
                () -> assertFalse(body.contains(forgedExternalId)),
                () -> assertFalse(body.contains("1999")),
                () -> assertFalse(body.contains(OTHER))
        );
    }

    // ---- owner comes only from X-User-Id: query, form and body forgeries -------------------------------------

    private void assertStoredOnlyForTheCaller(String responseBody) {
        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(repository, times(1)).save(saved.capture());
        assertEquals(USER, saved.getValue().getUserId(), "the row belongs to the X-User-Id caller");
        verify(repository, never()).save(argThat(e -> OTHER.equals(e.getUserId())));
        assertFalse(responseBody.contains(OTHER), responseBody);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "userId", "user-id", "owner", "X-User-Id", "x-user-id"})
    void postIgnoresAUserIdInTheQueryStringAndStoresTheRowForTheHeaderUser(String parameter) throws Exception {
        String body = perform(post("/expense/v1/expenses?" + parameter + "=" + OTHER).header("X-User-Id", USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 7.5, \"merchant\": \"Query forger\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertStoredOnlyForTheCaller(body);
    }

    @Test
    void postIgnoresAUserIdInTheQueryStringTheParameterMapAndTheBodyAllAtOnce() throws Exception {
        String body = perform(post("/expense/v1/expenses?user_id=" + OTHER + "&userId=" + OTHER).header("X-User-Id", USER)
                .param("user_id", OTHER).param("userId", OTHER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 7.5, \"user_id\": \"" + OTHER + "\", \"userId\": \"" + OTHER + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertStoredOnlyForTheCaller(body);
    }

    @Test
    void postIgnoresARequestParameterUserIdEvenWithAJsonBody() throws Exception {
        // MockMvc .param() is what a servlet container exposes for a query string or a form field
        String body = perform(post("/expense/v1/expenses").header("X-User-Id", USER).param("user_id", OTHER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 7.5}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertStoredOnlyForTheCaller(body);
    }

    @Test
    void aFormEncodedUserIdCanNeitherSetTheOwnerNorCreateARow() throws Exception {
        // the endpoint only reads JSON: a form post is refused outright and nothing is stored for anyone
        perform(post("/expense/v1/expenses").header("X-User-Id", USER)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("user_id=" + OTHER + "&amount=5&merchant=Form+forger"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        perform(post("/expense/v1/expenses?user_id=" + OTHER).header("X-User-Id", USER)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("user_id=" + OTHER + "&amount=5"))
                .andExpect(status().isUnsupportedMediaType());
        perform(post("/expense/v1/expenses").header("X-User-Id", USER).param("user_id", OTHER).param("amount", "5")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnsupportedMediaType());

        verify(repository, never()).save(any());
    }

    @Test
    void aUserIdAndAmountRequestParameterNeverOverrideTheJsonBodyOrTheHeader() throws Exception {
        // request parameters (query or form) carry a user_id and an amount; only the header and the JSON body count
        String body = perform(post("/expense/v1/expenses").header("X-User-Id", USER)
                .param("user_id", OTHER).param("amount", "999")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 3, \"merchant\": \"user_id=" + OTHER + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(3.0))
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(repository).save(saved.capture());
        assertEquals(USER, saved.getValue().getUserId());
        assertEquals(0, new BigDecimal("3").compareTo(saved.getValue().getAmount()), "amount comes from the body only");
        assertNotNull(body);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {}                                                  | amount
            {"amount": null}                                    | amount
            {"amount": 0}                                       | amount
            {"amount": 0.00}                                    | amount
            {"amount": -5}                                      | amount
            {"amount": 0.004}                                   | amount
            {"amount": "abc"}                                   | amount
            {"amount": ""}                                      | amount
            {"amount": "NaN"}                                   | amount
            {"amount": 100000000000000000}                      | amount
            {"amount": 99999999999999999.995}                   | amount
            {"amount": 1, "currency": "XYZ"}                    | currency
            {"amount": 1, "currency": "inr"}                    | currency
            {"amount": 1, "currency": ""}                       | currency
            {"amount": 1, "currency": "RUPEES"}                 | currency
            {"amount": 1, "category": "food"}                   | category
            {"amount": 1, "category": "NOPE"}                   | category
            {"amount": 1, "category": ""}                       | category
            {"amount": 1, "txn_type": "REFUND"}                 | txn_type
            {"amount": 1, "txn_type": "debit"}                  | txn_type
            {"amount": 1, "txn_date": "1900-01-01"}             | txn_date
            {"amount": 1, "txn_date": "+10000-01-01"}           | txn_date
            """)
    void postRejectsInvalidInputWithValidationFailedAndTheOffendingField(String json, String field) throws Exception {
        perform(postAs(USER, json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/expense/v1/expenses"))
                .andExpect(jsonPath("$.timestamp", org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.details[*].field", hasItem(field)))
                .andExpect(jsonPath("$.details[0].message").isNotEmpty());

        verify(repository, never()).save(any());
    }

    @Test
    void postRejectsATooLongMerchantAndReportsEveryBadFieldAtOnce() throws Exception {
        perform(postAs(USER, "{\"amount\": 1, \"merchant\": \"" + "m".repeat(256) + "\", \"category\": \"x\", \"currency\": \"q\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", containsInAnyOrder("merchant", "category", "currency")));

        perform(postAs(USER, "{\"amount\": 1, \"merchant\": \"" + "m".repeat(255) + "\"}")).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount\": 1",                      // truncated
            "not json",
            "[1, 2]",
            "{\"amount\": true}",
            "{\"amount\": [1]}",
            "{\"amount\": {\"v\": 1}}",
            "{\"amount\": 1, \"txn_date\": \"2026-13-45\"}",
            "{\"amount\": 1, \"txn_date\": [1]}",
            "{\"amount\": 1, \"merchant\": {\"a\": 1}}",
            "null",
            ""
    })
    void postRejectsMalformedOrMistypedJsonWithBadRequestAndNoEcho(String json) throws Exception {
        perform(postAs(USER, json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message", not(containsString("Jackson"))))
                .andExpect(jsonPath("$.message", not(containsString("Unrecognized"))))
                .andExpect(jsonPath("$.message", not(containsString("com."))));

        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount\":1}garbage",
            "{\"amount\": 1} garbage",
            "{\"amount\":1}{\"amount\":2}",
            "{\"amount\":1} {\"amount\":2}",
            "{\"amount\":1}\n{\"amount\":2}",
            "{\"amount\":1}{\"amount\":1}{\"amount\":1}",
            "{\"amount\":1}}",
            "{\"amount\":1},",
            "{\"amount\":1}[1]",
            "{\"amount\":1}123",
            "{\"amount\":1}\"x\"",
            "{\"amount\":1}null",
            "{\"amount\":1}true"
    })
    void postRejectsTrailingContentAfterTheJsonObjectWithBadRequestAndStoresNothing(String json) throws Exception {
        perform(postAs(USER, json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/expense/v1/expenses"))
                .andExpect(jsonPath("$.message", not(containsString("garbage"))))
                .andExpect(jsonPath("$.message", not(containsString("Jackson"))))
                .andExpect(jsonPath("$.details").doesNotExist());

        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"amount\":1}", "{\"amount\":1}\n", "  {\"amount\":1}  \r\n\t ", "{\"amount\":1}   "})
    void postStillAcceptsTrailingWhitespaceAfterTheJsonObject(String json) throws Exception {
        perform(postAs(USER, json)).andExpect(status().isCreated());

        verify(repository, times(1)).save(any());
    }

    @Test
    void postRejectsAnUnsupportedContentTypeWithThePinnedBody() throws Exception {
        perform(post("/expense/v1/expenses").header("X-User-Id", USER)
                .contentType(MediaType.TEXT_PLAIN).content("amount=5"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void wrongMethodOnAKnownPathGetsThePinnedBody() throws Exception {
        perform(get("/expense/v1/expenses").header("X-User-Id", USER).with(r -> {
            r.setMethod("DELETE");
            return r;
        }))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.status").value(405));
    }

    // ---- DoS ----------------------------------------------------------------------------------------------

    @Test
    void absurdAmountLiteralsAreRejectedFastInsideJacksonBeforeTheServiceIsReached() throws Exception {
        // warm-up: class loading and Jackson initialisation must not be charged to the timed calls
        perform(postAs(USER, "{\"amount\": 1}")).andExpect(status().isCreated());
        clearInvocations(repository);

        for (String literal : new String[]{"1e999999999", "1E+50000000", "1e-999999999", "1E-50000000",
                "9.99e2147483647", "-1e999999999", "\"1e999999999\"", "\"1E+50000000\""}) {
            long start = System.nanoTime();
            perform(postAs(USER, "{\"amount\": " + literal + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details[0].field").value("amount"))
                    .andExpect(jsonPath("$.details[0].message", not(containsString("999"))));
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            assertTrue(took.compareTo(Duration.ofSeconds(1)) < 0, literal + " took " + took);
        }

        verifyNoInteractions(repository);
    }

    // ---- errors -------------------------------------------------------------------------------------------

    @Test
    void anUnexpectedFailureIs500InternalWithoutLeakingAnything() throws Exception {
        // doThrow, not when(save(any())): re-stubbing through when() would invoke the echo answer with a null row
        doThrow(new IllegalStateException(
                "could not execute statement [insert into expense ... password=hunter2] at com.mysql.cj.jdbc"))
                .when(repository).save(any());

        String body = perform(postAs(USER, "{\"amount\": 5}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.path").value("/expense/v1/expenses"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("hunter2") || body.contains("insert into") || body.contains("com.mysql")
                || body.contains("IllegalStateException") || body.contains("at "), body);
    }

    @Test
    void aFailingQueryOnTheListEndpointIsAlso500InternalNot404() throws Exception {
        when(repository.search(any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));

        perform(listAs(USER, ""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message", not(containsString("db down"))));
    }

    @Test
    void errorBodiesHaveExactlyThePinnedProperties() throws Exception {
        perform(listAs(USER, "?size=999"))
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.details").exists());

        perform(get("/expense/v1/nope").header("X-User-Id", USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/expense/v1/nope"));
    }
}
