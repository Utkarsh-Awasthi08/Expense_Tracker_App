package com.expenseTracker.userService.Controller;

import com.expenseTracker.userService.Entities.UserInfo;
import com.expenseTracker.userService.Repository.UserRepository;
import com.expenseTracker.userService.Service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice for GET/PUT /user/v1/me: the real filter, controller, advice and UserService run against an
 * in-memory stand-in for the repository, so partial-update and identity behaviour is exercised for real.
 */
@WebMvcTest(UserController.class)
@Import(UserService.class)
class UserControllerTest {

    private static final String ALICE = "3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91";
    private static final String BOB = "8d0c1f2a-4b3e-4a6d-9c7f-1e2d3c4b5a69";
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private UserRepository repository;

    private final Map<String, UserInfo> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        store.clear();
        store.put(ALICE, user(1L, ALICE, "Alice", "Anderson", "919876500001", "alice@example.com"));
        store.put(BOB, user(2L, BOB, "Bob", "Brown", "919876500002", "bob@example.com"));
        when(repository.findByUserId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        when(repository.findByUserIdForUpdate(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        when(repository.save(any(UserInfo.class))).thenAnswer(inv -> {
            UserInfo saved = inv.getArgument(0);
            store.put(saved.getUserId(), saved);
            return saved;
        });
    }

    private static UserInfo user(Long id, String userId, String first, String last, String phone, String email) {
        return UserInfo.builder().id(id).userId(userId).firstName(first).lastName(last).phoneNumber(phone)
                .email(email).profilePicture("https://img.example.com/" + first + ".png")
                .defaultCurrency("INR").timezone("Asia/Kolkata").createdAt(CREATED).updatedAt(CREATED).build();
    }

    private MockHttpServletRequestBuilder putMe(String userId, String json) {
        return put("/user/v1/me").header("X-User-Id", userId).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    // ---- identity ----------------------------------------------------------------------------------------------

    @Test
    void getWithoutHeaderIs401WithPinnedBodyAndBearerChallenge() throws Exception {
        mvc.perform(get("/user/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/user/v1/me"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void putWithNonUuidHeaderIs401AndNothingChanges() throws Exception {
        mvc.perform(putMe("not-a-uuid", "{\"first_name\":\"Mallory\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(store.get(ALICE).getFirstName()).isEqualTo("Alice");
    }

    // ---- GET ---------------------------------------------------------------------------------------------------

    @Test
    void getMeReturnsExactlyTheSnakeCaseProfileOfTheCaller() throws Exception {
        MvcResult result = mvc.perform(get("/user/v1/me").header("X-User-Id", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(ALICE))
                .andExpect(jsonPath("$.first_name").value("Alice"))
                .andExpect(jsonPath("$.last_name").value("Anderson"))
                .andExpect(jsonPath("$.phone_number").value("919876500001"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.profile_picture").value("https://img.example.com/Alice.png"))
                .andExpect(jsonPath("$.default_currency").value("INR"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("user_id", "first_name", "last_name",
                "phone_number", "email", "profile_picture", "default_currency", "timezone", "monthly_budget");
        assertThat(body.get("phone_number").isTextual()).isTrue();
    }

    @Test
    void getMeAcceptsUpperCaseUuidHeader() throws Exception {
        mvc.perform(get("/user/v1/me").header("X-User-Id", ALICE.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(ALICE));
    }

    @Test
    void getMeIs404UntilTheCreatedEventHasLanded() throws Exception {
        String carol = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        mvc.perform(get("/user/v1/me").header("X-User-Id", carol))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/user/v1/me"));
    }

    // ---- PUT: partial update -----------------------------------------------------------------------------------

    @Test
    void putChangesOnlyTheFieldsPresentInTheBody() throws Exception {
        mvc.perform(putMe(ALICE, "{\"first_name\":\"Alicia\",\"timezone\":\"Europe/Berlin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value("Alicia"))
                .andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.last_name").value("Anderson"))
                .andExpect(jsonPath("$.phone_number").value("919876500001"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.default_currency").value("INR"));
        UserInfo alice = store.get(ALICE);
        assertThat(alice.getFirstName()).isEqualTo("Alicia");
        assertThat(alice.getTimezone()).isEqualTo("Europe/Berlin");
        assertThat(alice.getLastName()).isEqualTo("Anderson");
        assertThat(alice.getPhoneNumber()).isEqualTo("919876500001");
        assertThat(alice.getEmail()).isEqualTo("alice@example.com");
        assertThat(alice.getProfilePicture()).isEqualTo("https://img.example.com/Alice.png");
        assertThat(alice.getDefaultCurrency()).isEqualTo("INR");
    }

    @Test
    void putCanChangeEveryEditableFieldAndUpperCasesTheCurrency() throws Exception {
        mvc.perform(putMe(ALICE, """
                {"first_name":"  Ally ","last_name":"Smith","phone_number":"+447911123456",
                 "email":"ally@example.org","profile_picture":"https://cdn.example.com/a.png",
                 "default_currency":"usd","timezone":"America/New_York"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value("Ally"))
                .andExpect(jsonPath("$.last_name").value("Smith"))
                .andExpect(jsonPath("$.phone_number").value("+447911123456"))
                .andExpect(jsonPath("$.email").value("ally@example.org"))
                .andExpect(jsonPath("$.profile_picture").value("https://cdn.example.com/a.png"))
                .andExpect(jsonPath("$.default_currency").value("USD"))
                .andExpect(jsonPath("$.timezone").value("America/New_York"));
        assertThat(store.get(ALICE).getDefaultCurrency()).isEqualTo("USD");
    }

    @Test
    void emptyBodyObjectAndExplicitNullsChangeNothing() throws Exception {
        mvc.perform(putMe(ALICE, "{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value("Alice"));
        mvc.perform(putMe(ALICE, "{\"first_name\":null,\"default_currency\":null,\"timezone\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value("Alice"))
                .andExpect(jsonPath("$.default_currency").value("INR"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"));
    }

    @Test
    void numericPhoneNumberInBodyIsAcceptedAsText() throws Exception {
        mvc.perform(putMe(ALICE, "{\"phone_number\":9876543210}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone_number").value("9876543210"));
    }

    // ---- PUT: identity comes only from the header --------------------------------------------------------------

    @Test
    void bodyUserIdIdAndCreatedAtAreIgnoredAndCannotTouchAnotherUsersRow() throws Exception {
        UserInfo bobBefore = snapshot(store.get(BOB));
        mvc.perform(putMe(ALICE, """
                {"user_id":"%s","id":2,"created_at":"1999-01-01T00:00:00Z","updated_at":"1999-01-01T00:00:00Z",
                 "first_name":"Changed"}""".formatted(BOB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(ALICE))
                .andExpect(jsonPath("$.first_name").value("Changed"));
        UserInfo alice = store.get(ALICE);
        assertThat(alice.getFirstName()).isEqualTo("Changed");
        assertThat(alice.getUserId()).isEqualTo(ALICE);
        assertThat(alice.getId()).isEqualTo(1L);
        assertThat(alice.getCreatedAt()).isEqualTo(CREATED);
        UserInfo bob = store.get(BOB);
        assertThat(bob).usingRecursiveComparison().isEqualTo(bobBefore);
        assertThat(bob.getFirstName()).isEqualTo("Bob");
    }

    @Test
    void bodyUserIdOfAnotherUserWithoutARowForTheCallerNeverCreatesOrUpdates() throws Exception {
        String carol = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        UserInfo bobBefore = snapshot(store.get(BOB));
        mvc.perform(putMe(carol, "{\"user_id\":\"" + BOB + "\",\"first_name\":\"Hijack\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(store).doesNotContainKey(carol).hasSize(2);
        assertThat(store.get(BOB)).usingRecursiveComparison().isEqualTo(bobBefore);
    }

    @Test
    void putWhenNoRowExistsIs404AndNeverCreates() throws Exception {
        String carol = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        mvc.perform(putMe(carol, "{\"first_name\":\"Carol\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(store).doesNotContainKey(carol);
    }

    // ---- PUT: validation ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} = {1} is rejected")
    @CsvSource(delimiter = '|', value = {
            "phone_number|12345",
            "phone_number|+1234567890123456",
            "phone_number|98765 00012",
            "phone_number|abc1234567",
            "phone_number|++123456789",
            "email|not-an-email",
            "email|'   '",
            "default_currency|XYZ1",
            "default_currency|ZZZ",
            "default_currency|us",
            "default_currency|R$?",
            "timezone|Mars/Olympus",
            "timezone|asia/nowhere",
            "timezone|not a zone",
            "first_name|'   '",
            "last_name|'   '"})
    void invalidValuesAre400ValidationFailedWithSnakeCaseFieldDetail(String field, String value) throws Exception {
        MvcResult result = mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of(field, value))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/user/v1/me"))
                .andExpect(jsonPath("$.details[0].field").value(field))
                .andExpect(jsonPath("$.details[0].message").isNotEmpty())
                .andReturn();
        // Rejected input is never echoed back and nothing was stored.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Mars/Olympus");
        assertThat(store.get(ALICE).getFirstName()).isEqualTo("Alice");
        assertThat(store.get(ALICE).getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void tooLongValuesAreRejected() throws Exception {
        String tooLongName = "x".repeat(101);
        String tooLongPicture = "https://e.com/" + "p".repeat(500);
        mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of("first_name", tooLongName,
                        "profile_picture", tooLongPicture))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.length()").value(2));
        String at = "a".repeat(250) + "@example.com";
        mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of("email", at))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("email"));
    }

    @Test
    void boundaryLengthsAreAccepted() throws Exception {
        String name100 = "n".repeat(100);
        String picture512 = "https://e.com/" + "p".repeat(512 - "https://e.com/".length());
        mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of("first_name", name100,
                        "profile_picture", picture512, "phone_number", "1234567", "timezone", "UTC"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value(name100))
                .andExpect(jsonPath("$.phone_number").value("1234567"));
    }

    @Test
    void severalInvalidFieldsAreAllReported() throws Exception {
        mvc.perform(putMe(ALICE, "{\"email\":\"nope\",\"phone_number\":\"1\",\"timezone\":\"Nowhere/Land\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.length()").value(3));
    }

    @Test
    void malformedJsonIs400BadRequestWithoutParserDetails() throws Exception {
        MvcResult result = mvc.perform(putMe(ALICE, "{\"first_name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path").value("/user/v1/me"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Jackson").doesNotContain("Unexpected")
                .doesNotContain("com.fasterxml").doesNotContain("Exception");
    }

    @Test
    void wrongJsonShapeAndMissingBodyAre400BadRequest() throws Exception {
        mvc.perform(putMe(ALICE, "[1,2,3]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(putMe(ALICE, "{\"first_name\":{\"nested\":true}}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(put("/user/v1/me").header("X-User-Id", ALICE).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // ---- PUT: locking read --------------------------------------------------------------------------------------

    @Test
    void putReadsTheRowWithTheLockingQueryNotThePlainOne() throws Exception {
        mvc.perform(putMe(ALICE, "{\"first_name\":\"Locked\"}")).andExpect(status().isOk());
        verify(repository).findByUserIdForUpdate(ALICE);
        verify(repository, never()).findByUserId(anyString());
    }

    @Test
    void getReadsWithThePlainQueryAndNeverTakesTheWriteLock() throws Exception {
        mvc.perform(get("/user/v1/me").header("X-User-Id", ALICE)).andExpect(status().isOk());
        verify(repository).findByUserId(ALICE);
        verify(repository, never()).findByUserIdForUpdate(anyString());
    }

    @Test
    void putForAMissingRowStill404sThroughTheLockingQuery() throws Exception {
        String carol = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
        mvc.perform(putMe(carol, "{\"first_name\":\"Carol\"}")).andExpect(status().isNotFound());
        verify(repository).findByUserIdForUpdate(carol);
        verify(repository, never()).save(any(UserInfo.class));
    }

    // ---- PUT: input hygiene (Unicode blanks, control characters, pseudo currencies) --------------------------------

    private static final String[] TEXT_FIELDS = {"first_name", "last_name", "email", "profile_picture"};

    private void assertRejectedAsValidationFailed(String field, String value) throws Exception {
        MvcResult result = mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of(field, value))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/user/v1/me"))
                .andExpect(jsonPath("$.details[0].field").value(field))
                .andReturn();
        JsonNode details = mapper.readTree(result.getResponse().getContentAsString()).get("details");
        for (JsonNode detail : details) {
            assertThat(detail.get("field").asText()).isEqualTo(field);
        }
        assertThat(store.get(ALICE).getFirstName()).isEqualTo("Alice");
        assertThat(store.get(ALICE).getLastName()).isEqualTo("Anderson");
        assertThat(store.get(ALICE).getEmail()).isEqualTo("alice@example.com");
        assertThat(store.get(ALICE).getProfilePicture()).isEqualTo("https://img.example.com/Alice.png");
    }

    @ParameterizedTest(name = "{0} made only of U+{1} is blank")
    @CsvSource({
            "first_name,00A0", "first_name,2003", "first_name,200B", "first_name,3000", "first_name,FEFF",
            "first_name,202F", "first_name,2028", "first_name,2060", "first_name,3164",
            "last_name,00A0", "last_name,2003", "last_name,200B", "last_name,3000", "last_name,FEFF",
            "last_name,202F", "last_name,2028", "last_name,2060", "last_name,3164",
            "profile_picture,00A0", "profile_picture,2003", "profile_picture,200B", "profile_picture,3000",
            "email,00A0", "email,2003", "email,200B"})
    void textMadeOnlyOfUnicodeWhitespaceOrInvisibleCharactersIsBlank(String field, String hex) throws Exception {
        String ch = new String(Character.toChars(Integer.parseInt(hex, 16)));
        assertRejectedAsValidationFailed(field, ch);
        assertRejectedAsValidationFailed(field, ch.repeat(5));
    }

    @Test
    void mixedUnicodeBlanksAreBlankAndTheEmptyStringIsRejectedToo() throws Exception {
        for (String field : TEXT_FIELDS) {
            assertRejectedAsValidationFailed(field, " \u00A0\u2003\u200B\u3000\uFEFF ");
            assertRejectedAsValidationFailed(field, "");
        }
    }

    @ParameterizedTest(name = "{0} containing control character U+{1} is rejected")
    @CsvSource({
            "first_name,0000", "first_name,0007", "first_name,0009", "first_name,000A", "first_name,000D",
            "first_name,001B", "first_name,007F", "first_name,0085", "first_name,009F", "first_name,2028",
            "first_name,2029",
            "last_name,0000", "last_name,0007", "last_name,0009", "last_name,000A", "last_name,000D",
            "last_name,001B", "last_name,007F", "last_name,0085", "last_name,009F", "last_name,2028",
            "last_name,2029",
            "profile_picture,0000", "profile_picture,0009", "profile_picture,000A", "profile_picture,000D",
            "profile_picture,007F", "profile_picture,0085", "profile_picture,2028",
            "email,0000", "email,0009", "email,000A", "email,000D", "email,007F", "email,0085"})
    void controlCharactersAreRejectedAnywhereInTheValue(String field, String hex) throws Exception {
        String ch = new String(Character.toChars(Integer.parseInt(hex, 16)));
        String core = switch (field) {
            case "email" -> "ally@example.org";
            case "profile_picture" -> "https://cdn.example.com/a.png";
            default -> "Ally";
        };
        String withControl = switch (field) {
            case "email" -> "al" + ch + "ly@example.org";
            case "profile_picture" -> "https://cdn.example.com/a" + ch + ".png";
            default -> "Al" + ch + "ly";
        };
        assertRejectedAsValidationFailed(field, withControl);
        assertRejectedAsValidationFailed(field, ch + core);
        assertRejectedAsValidationFailed(field, core + ch);
    }

    @Test
    void controlCharacterViolationsAreReportedWithAControlCharacterMessageNotAnEchoOfTheValue() throws Exception {
        MvcResult result = mvc.perform(putMe(ALICE, "{\"first_name\":\"Al\\u0000ice\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("first_name"))
                .andExpect(jsonPath("$.details[0].message").value("must not contain control characters"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ice").doesNotContain("\u0000");
    }

    @Test
    void aControlCharacterInOneFieldRejectsTheWholeRequestAndNothingIsStored() throws Exception {
        mvc.perform(putMe(ALICE, "{\"first_name\":\"Fine\",\"last_name\":\"Bad\\u0000\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0].field").value("last_name"));
        assertThat(store.get(ALICE).getFirstName()).isEqualTo("Alice");
        assertThat(store.get(ALICE).getTimezone()).isEqualTo("Asia/Kolkata");
        verify(repository, never()).save(any(UserInfo.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XXX", "XTS", "xxx", "xts", "Xxx", "xTs"})
    void pseudoCurrenciesXxxAndXtsAreRejectedInAnyCase(String currency) throws Exception {
        mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of("default_currency", currency))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("default_currency"))
                .andExpect(jsonPath("$.details[0].message").value("must be a valid ISO-4217 currency code"));
        assertThat(store.get(ALICE).getDefaultCurrency()).isEqualTo("INR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "eur", "JPY", "gbp", "INR", "CHF", "XAU", "XDR", "XOF"})
    void realIsoCurrenciesAreStillAcceptedAndUpperCased(String currency) throws Exception {
        mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of("default_currency", currency))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.default_currency").value(currency.toUpperCase()));
        assertThat(store.get(ALICE).getDefaultCurrency()).isEqualTo(currency.toUpperCase());
    }

    @Test
    void legitimateInvisibleCharactersInsideANameAreKeptAndOuterBlanksAreStripped() throws Exception {
        // A zero-width joiner between letters is part of real names (Indic and Persian scripts); only text that is
        // blank as a whole is rejected, and only the outer blanks are stripped before storing.
        mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of(
                        "first_name", "\u00A0\u2003Ali\u200Dce\u200B\u3000",
                        "last_name", "\u3000Anderson Jr\u00A0"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value("Ali\u200Dce"))
                .andExpect(jsonPath("$.last_name").value("Anderson Jr"));
        assertThat(store.get(ALICE).getFirstName()).isEqualTo("Ali\u200Dce");
        assertThat(store.get(ALICE).getLastName()).isEqualTo("Anderson Jr");
    }

    @Test
    void namesInOtherScriptsAndAccentsAreAccepted() throws Exception {
        mvc.perform(putMe(ALICE, mapper.writeValueAsString(Map.of(
                        "first_name", "\u0905\u0928\u093F\u0932", "last_name", "M\u00FCller-O\u2019Brien"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first_name").value("\u0905\u0928\u093F\u0932"))
                .andExpect(jsonPath("$.last_name").value("M\u00FCller-O\u2019Brien"));
    }

    // ---- removed endpoints and framework errors ----------------------------------------------------------------

    @Test
    void oldCreateUpdateAndGetUserEndpointsAreGone() throws Exception {
        String body = "{\"user_id\":\"" + BOB + "\",\"first_name\":\"Hijack\"}";
        UserInfo bobBefore = snapshot(store.get(BOB));
        mvc.perform(post("/user/v1/createUpdate").header("X-User-Id", ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/user/v1/createUpdate"));
        mvc.perform(get("/user/v1/getUser").header("X-User-Id", ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(store.get(BOB)).usingRecursiveComparison().isEqualTo(bobBefore);
    }

    @Test
    void unsupportedMethodAndMediaTypeUseThePinnedBody() throws Exception {
        mvc.perform(delete("/user/v1/me").header("X-User-Id", ALICE))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path").value("/user/v1/me"));
        mvc.perform(put("/user/v1/me").header("X-User-Id", ALICE).contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unexpectedFailuresAre500InternalWithNoLeaks() throws Exception {
        when(repository.findByUserId(anyString()))
                .thenThrow(new IllegalStateException("could not execute statement SELECT secret_column FROM user_info"));
        MvcResult result = mvc.perform(get("/user/v1/me").header("X-User-Id", ALICE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.path").value("/user/v1/me"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("SELECT").doesNotContain("secret_column").doesNotContain("IllegalState")
                .doesNotContain("at com.").doesNotContain("stackTrace");
    }

    private static UserInfo snapshot(UserInfo u) {
        return UserInfo.builder().id(u.getId()).userId(u.getUserId()).firstName(u.getFirstName())
                .lastName(u.getLastName()).phoneNumber(u.getPhoneNumber()).email(u.getEmail())
                .profilePicture(u.getProfilePicture()).defaultCurrency(u.getDefaultCurrency())
                .timezone(u.getTimezone()).createdAt(u.getCreatedAt()).updatedAt(u.getUpdatedAt()).build();
    }
}
