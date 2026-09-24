package com.expenseTracker.userService;

import com.expenseTracker.userService.Deserializer.UserInfoDeserializer;
import com.expenseTracker.userService.Entities.UserInfo;
import com.expenseTracker.userService.Entities.UserInfoDTO;
import com.expenseTracker.userService.Repository.UserRepository;
import com.expenseTracker.userService.Service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application context against a real MySQL 8.4: Flyway creates the schema, Hibernate runs in
 * ddl-auto=validate, Kafka listeners are off. Skipped (not failed) when Docker is unavailable.
 * The JVM default zone is forced to a non-UTC zone so UTC storage is proven, not accidental.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class UserServiceMySqlTests {

    private static final String ALICE = "3f6c2b7e-9d1a-4c58-8e0b-5a1d7c4f2e91";
    private static final String BOB = "8d0c1f2a-4b3e-4a6d-9c7f-1e2d3c4b5a69";
    private static final Instant CREATED = Instant.parse("2026-09-20T10:15:30.123456Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static TimeZone originalZone;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeAll
    static void useNonUtcJvmZone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    @AfterAll
    static void restoreJvmZone() {
        TimeZone.setDefault(originalZone);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository repository;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private Environment environment;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @LocalServerPort
    private int port;

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM user_info");
    }

    private UserInfo saved(String userId, String first, String last) {
        return repository.save(UserInfo.builder().userId(userId).firstName(first).lastName(last)
                .phoneNumber("919876500001").email(first.toLowerCase() + "@example.com")
                .createdAt(CREATED).updatedAt(CREATED).build());
    }

    private Map<String, Object> rawRow(String userId) {
        return jdbc.queryForMap("SELECT * FROM user_info WHERE user_id = ?", userId);
    }

    // ---- schema --------------------------------------------------------------------------------------------------

    @Test
    void flywayAppliedV1AndTheSchemaMatchesTheContract() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class);
        assertThat(applied).isEqualTo(1);

        Map<String, String> types = jdbc.queryForList(
                        "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY "
                                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                                + "AND TABLE_NAME = 'user_info'").stream()
                .collect(Collectors.toMap(r -> (String) r.get("COLUMN_NAME"),
                        r -> r.get("COLUMN_TYPE") + "|" + r.get("IS_NULLABLE") + "|" + r.get("COLUMN_DEFAULT")));
        assertThat(types).containsOnlyKeys("id", "user_id", "first_name", "last_name", "phone_number", "email",
                "profile_picture", "default_currency", "timezone", "monthly_budget", "created_at", "updated_at");
        assertThat(types.get("id")).startsWith("bigint");
        assertThat(types.get("user_id")).isEqualTo("varchar(36)|NO|null");
        assertThat(types.get("first_name")).isEqualTo("varchar(100)|YES|null");
        assertThat(types.get("last_name")).isEqualTo("varchar(100)|YES|null");
        assertThat(types.get("phone_number")).isEqualTo("varchar(20)|YES|null");
        assertThat(types.get("email")).isEqualTo("varchar(254)|YES|null");
        assertThat(types.get("profile_picture")).isEqualTo("varchar(512)|YES|null");
        assertThat(types.get("default_currency")).isEqualTo("varchar(3)|NO|INR");
        assertThat(types.get("timezone")).isEqualTo("varchar(64)|NO|Asia/Kolkata");
        assertThat(types.get("monthly_budget")).isEqualTo("decimal(19,2)|YES|null");
        assertThat(types.get("created_at")).startsWith("datetime(6)|NO");
        assertThat(types.get("updated_at")).startsWith("datetime(6)|NO");
    }

    @Test
    void persistenceSettingsFollowThePinnedBuildHazards() {
        // The context booting proves Flyway + validate agree with the entity; these guard the pinned settings.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.dialect")).isNull();
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto")).isNull();
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }

    @Test
    void userIdHasARealUniqueIndex() {
        List<Map<String, Object>> idx = jdbc.queryForList(
                "SELECT INDEX_NAME, NON_UNIQUE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'user_info' AND COLUMN_NAME = 'user_id'");
        assertThat(idx).hasSize(1);
        assertThat(((Number) idx.get(0).get("NON_UNIQUE")).intValue()).isZero();
    }

    @Test
    void insertingADuplicateUserIdIsRejectedByTheDatabase() {
        String sql = "INSERT INTO user_info (user_id, created_at, updated_at) VALUES (?, NOW(6), NOW(6))";
        jdbc.update(sql, ALICE);
        assertThatThrownBy(() -> jdbc.update(sql, ALICE)).isInstanceOf(DuplicateKeyException.class);

        assertThatThrownBy(() -> saved(ALICE, "Second", "Alice")).isInstanceOf(DataIntegrityViolationException.class);

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE user_id = ?", Integer.class, ALICE);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void columnDefaultsApplyForRowsInsertedWithoutCurrencyAndTimezone() {
        jdbc.update("INSERT INTO user_info (user_id, created_at, updated_at) VALUES (?, NOW(6), NOW(6))", ALICE);
        UserInfo row = repository.findByUserId(ALICE).orElseThrow();
        assertThat(row.getDefaultCurrency()).isEqualTo("INR");
        assertThat(row.getTimezone()).isEqualTo("Asia/Kolkata");
        assertThat(row.getPhoneNumber()).isNull();
    }

    // ---- UTC storage ---------------------------------------------------------------------------------------------

    @Test
    void timestampsAreStoredInUtcEvenWhenTheJvmZoneIsNot() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Kolkata");
        saved(ALICE, "Alice", "Anderson");

        Map<String, Object> text = jdbc.queryForMap("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') AS c, "
                + "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s.%f') AS u FROM user_info WHERE user_id = ?", ALICE);
        assertThat(text.get("c")).isEqualTo("2026-09-20 10:15:30.123456");
        assertThat(text.get("u")).isEqualTo("2026-09-20 10:15:30.123456");

        UserInfo back = repository.findByUserId(ALICE).orElseThrow();
        assertThat(back.getCreatedAt()).isEqualTo(CREATED);
        assertThat(back.getUpdatedAt()).isEqualTo(CREATED);
    }

    @Test
    void generatedTimestampsAreUtcNowNotLocalNow() {
        UserInfo created = repository.save(UserInfo.builder().userId(ALICE).build());
        assertThat(created.getId()).isNotNull();
        Long drift = jdbc.queryForObject(
                "SELECT ABS(TIMESTAMPDIFF(SECOND, created_at, UTC_TIMESTAMP(6))) FROM user_info WHERE user_id = ?",
                Long.class, ALICE);
        // Kolkata is UTC+5:30 (19800 s); a local-time write would show that offset.
        assertThat(drift).isLessThan(300L);
    }

    // ---- PUT /me against the real database -----------------------------------------------------------------------

    @Test
    void putUpdatesOnlyTheCallersRowAndLeavesAnotherUsersRowUntouched() throws Exception {
        saved(ALICE, "Alice", "Anderson");
        saved(BOB, "Bob", "Brown");
        Map<String, Object> bobBefore = rawRow(BOB);
        Map<String, Object> aliceBefore = rawRow(ALICE);
        Long bobId = ((Number) bobBefore.get("id")).longValue();

        mvc.perform(put("/user/v1/me").header("X-User-Id", ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id":"%s","id":%d,"created_at":"1999-01-01T00:00:00Z",
                                 "first_name":"Alicia","default_currency":"eur","timezone":"Europe/Paris"}"""
                                .formatted(BOB, bobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(ALICE))
                .andExpect(jsonPath("$.first_name").value("Alicia"))
                .andExpect(jsonPath("$.last_name").value("Anderson"))
                .andExpect(jsonPath("$.default_currency").value("EUR"))
                .andExpect(jsonPath("$.timezone").value("Europe/Paris"));

        assertThat(rawRow(BOB)).isEqualTo(bobBefore);

        Map<String, Object> aliceAfter = rawRow(ALICE);
        assertThat(aliceAfter.get("first_name")).isEqualTo("Alicia");
        assertThat(aliceAfter.get("last_name")).isEqualTo("Anderson");
        assertThat(aliceAfter.get("default_currency")).isEqualTo("EUR");
        assertThat(aliceAfter.get("timezone")).isEqualTo("Europe/Paris");
        assertThat(aliceAfter.get("id")).isEqualTo(aliceBefore.get("id"));
        assertThat(aliceAfter.get("user_id")).isEqualTo(ALICE);
        assertThat(aliceAfter.get("created_at")).isEqualTo(aliceBefore.get("created_at"));
        assertThat(aliceAfter.get("phone_number")).isEqualTo("919876500001");
        assertThat(aliceAfter.get("email")).isEqualTo("alice@example.com");

        // updated_at moved and is stored as UTC (the JVM zone is UTC+5:30).
        assertThat(aliceAfter.get("updated_at")).isNotEqualTo(aliceBefore.get("updated_at"));
        Long drift = jdbc.queryForObject(
                "SELECT ABS(TIMESTAMPDIFF(SECOND, updated_at, UTC_TIMESTAMP(6))) FROM user_info WHERE user_id = ?",
                Long.class, ALICE);
        assertThat(drift).isLessThan(300L);

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM user_info", Integer.class);
        assertThat(total).isEqualTo(2);
    }

    @Test
    void getReturnsEachCallersOwnProfile() throws Exception {
        saved(ALICE, "Alice", "Anderson");
        saved(BOB, "Bob", "Brown");
        mvc.perform(get("/user/v1/me").header("X-User-Id", ALICE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.first_name").value("Alice"));
        mvc.perform(get("/user/v1/me").header("X-User-Id", BOB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.first_name").value("Bob"));
    }

    @Test
    void getAndPutAre404WhenNoRowExistsAndPutNeverCreatesOne() throws Exception {
        mvc.perform(get("/user/v1/me").header("X-User-Id", ALICE))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(put("/user/v1/me").header("X-User-Id", ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"first_name\":\"Ghost\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM user_info", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void invalidPutLeavesTheRowUntouched() throws Exception {
        saved(ALICE, "Alice", "Anderson");
        Map<String, Object> before = rawRow(ALICE);
        mvc.perform(put("/user/v1/me").header("X-User-Id", ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"first_name\":\"Changed\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(rawRow(ALICE)).isEqualTo(before);
    }

    // ---- concurrent PUT /me: the row lock serializes read-modify-write updates -------------------------------------

    private static final int CONCURRENCY_ROUNDS = 30;

    private final HttpClient http11 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private HttpResponse<String> putOverHttp(String userId, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/user/v1/me"))
                .header("X-User-Id", userId).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build();
        return http11.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getOverHttp(String userId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/user/v1/me"))
                .header("X-User-Id", userId).GET().build();
        return http11.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Fires all bodies at the same instant (one thread each, released by a barrier) and returns the statuses. */
    private List<Integer> putConcurrently(String userId, List<String> bodies) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(bodies.size());
        try {
            CyclicBarrier startTogether = new CyclicBarrier(bodies.size());
            List<Future<Integer>> futures = new ArrayList<>();
            for (String body : bodies) {
                futures.add(pool.submit(() -> {
                    startTogether.await(30, TimeUnit.SECONDS);
                    return putOverHttp(userId, body).statusCode();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPartialPutsOfDifferentFieldsAlwaysBothLandOverManyRounds() throws Exception {
        saved(ALICE, "Alice", "Anderson");
        for (int round = 0; round < CONCURRENCY_ROUNDS; round++) {
            jdbc.update("UPDATE user_info SET first_name = 'Base', last_name = 'Base' WHERE user_id = ?", ALICE);
            String first = "First" + round;
            String last = "Last" + round;
            List<Integer> statuses = putConcurrently(ALICE, List.of(
                    "{\"first_name\":\"" + first + "\"}", "{\"last_name\":\"" + last + "\"}"));
            assertThat(statuses).as("round %d statuses", round).containsExactly(200, 200);
            Map<String, Object> row = rawRow(ALICE);
            assertThat(row.get("first_name")).as("round %d first_name", round).isEqualTo(first);
            assertThat(row.get("last_name")).as("round %d last_name", round).isEqualTo(last);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_info", Integer.class)).isEqualTo(1);
    }

    @Test
    void sevenConcurrentPartialPutsOfSevenDifferentFieldsAllLand() throws Exception {
        saved(ALICE, "Alice", "Anderson");
        String[] currencies = {"USD", "EUR", "GBP", "JPY", "CHF"};
        String[] zones = {"Europe/Paris", "America/New_York", "Asia/Tokyo", "UTC", "Australia/Sydney"};
        for (int round = 0; round < 10; round++) {
            jdbc.update("UPDATE user_info SET first_name = 'Base', last_name = 'Base', phone_number = '1234567', "
                    + "email = 'base@example.com', profile_picture = 'https://base.example.com/x.png', "
                    + "default_currency = 'INR', timezone = 'Asia/Kolkata' WHERE user_id = ?", ALICE);
            String currency = currencies[round % currencies.length];
            String zone = zones[round % zones.length];
            List<Integer> statuses = putConcurrently(ALICE, List.of(
                    "{\"first_name\":\"F" + round + "\"}",
                    "{\"last_name\":\"L" + round + "\"}",
                    "{\"phone_number\":\"+4479111234" + round + "\"}",
                    "{\"email\":\"e" + round + "@example.org\"}",
                    "{\"profile_picture\":\"https://cdn.example.com/p" + round + ".png\"}",
                    "{\"default_currency\":\"" + currency + "\"}",
                    "{\"timezone\":\"" + zone + "\"}"));
            assertThat(statuses).as("round %d statuses", round).containsOnly(200).hasSize(7);
            Map<String, Object> row = rawRow(ALICE);
            assertThat(row.get("first_name")).as("round %d", round).isEqualTo("F" + round);
            assertThat(row.get("last_name")).as("round %d", round).isEqualTo("L" + round);
            assertThat(row.get("phone_number")).as("round %d", round).isEqualTo("+4479111234" + round);
            assertThat(row.get("email")).as("round %d", round).isEqualTo("e" + round + "@example.org");
            assertThat(row.get("profile_picture")).as("round %d", round)
                    .isEqualTo("https://cdn.example.com/p" + round + ".png");
            assertThat(row.get("default_currency")).as("round %d", round).isEqualTo(currency);
            assertThat(row.get("timezone")).as("round %d", round).isEqualTo(zone);
        }
    }

    @Test
    void concurrentPutsOfTheSameFieldLeaveExactlyOneOfTheSubmittedValues() throws Exception {
        saved(ALICE, "Alice", "Anderson");
        for (int round = 0; round < 10; round++) {
            List<Integer> statuses = putConcurrently(ALICE, List.of(
                    "{\"first_name\":\"Left" + round + "\",\"last_name\":\"LeftL" + round + "\"}",
                    "{\"first_name\":\"Right" + round + "\",\"last_name\":\"RightL" + round + "\"}"));
            assertThat(statuses).containsExactly(200, 200);
            Map<String, Object> row = rawRow(ALICE);
            // Whichever request ran last wins as a whole; the two are never interleaved field by field.
            boolean leftWon = row.get("first_name").equals("Left" + round);
            assertThat(row.get("first_name")).isEqualTo(leftWon ? "Left" + round : "Right" + round);
            assertThat(row.get("last_name")).isEqualTo(leftWon ? "LeftL" + round : "RightL" + round);
        }
    }

    @Test
    void theWriteLockBlocksAConcurrentPutUntilTheHoldingTransactionEndsButNeverBlocksAGet() throws Exception {
        saved(ALICE, "Alice", "Anderson");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                repository.findByUserIdForUpdate(ALICE).orElseThrow();
                holding.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("holder was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            Future<HttpResponse<String>> blockedPut = pool.submit(
                    () -> putOverHttp(ALICE, "{\"first_name\":\"AfterLock\"}"));

            // A plain read is a consistent (non-locking) read and is served while the row is locked.
            HttpResponse<String> get = getOverHttp(ALICE);
            assertThat(get.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(get.body()).get("first_name").asText()).isEqualTo("Alice");

            Thread.sleep(1500);
            assertThat(blockedPut.isDone()).as("PUT must wait for the row lock").isFalse();
            assertThat(rawRow(ALICE).get("first_name")).isEqualTo("Alice");

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            HttpResponse<String> put = blockedPut.get(30, TimeUnit.SECONDS);
            assertThat(put.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(put.body()).get("first_name").asText()).isEqualTo("AfterLock");
            assertThat(rawRow(ALICE).get("first_name")).isEqualTo("AfterLock");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void anUpdateOfOneFieldWritesOnlyThatColumnSoAnUnrelatedConcurrentEditSurvives() {
        // @DynamicUpdate: the UPDATE names only the dirty columns (plus updated_at). Simulate an out-of-band edit of
        // last_name that lands between this transaction's read and its flush: it must not be rewritten.
        saved(ALICE, "Alice", "Anderson");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            UserInfo user = repository.findByUserId(ALICE).orElseThrow();
            jdbc.update("UPDATE user_info SET last_name = 'Elsewhere' WHERE user_id = ?", ALICE);
            user.setFirstName("Changed");
            repository.save(user);
        });
        Map<String, Object> row = rawRow(ALICE);
        assertThat(row.get("first_name")).isEqualTo("Changed");
        assertThat(row.get("last_name")).isEqualTo("Elsewhere");
    }

    // ---- legacy Kafka consumer path -------------------------------------------------------------------------------

    @Test
    void oldFormatEventCreatesARowWithStringPhoneAndDefaultsAndNeverOverwritesLaterEdits() throws Exception {
        String oldFormat = """
                {"user_id":"%s","first_name":"Priya","last_name":"Sharma","phone_number":9876500012,
                 "email":"priya@example.com","username":"priya"}""".formatted(ALICE);
        UserInfoDTO event = new UserInfoDeserializer().deserialize("t", oldFormat.getBytes(StandardCharsets.UTF_8));

        userService.createOrUpdateUser(event);
        Map<String, Object> row = rawRow(ALICE);
        assertThat(row.get("phone_number")).isEqualTo("9876500012");
        assertThat(row.get("first_name")).isEqualTo("Priya");
        assertThat(row.get("default_currency")).isEqualTo("INR");
        assertThat(row.get("timezone")).isEqualTo("Asia/Kolkata");

        mvc.perform(put("/user/v1/me").header("X-User-Id", ALICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"first_name\":\"Edited\"}"))
                .andExpect(status().isOk());

        // A redelivered (stale) event must not clobber the edit.
        userService.createOrUpdateUser(event);
        assertThat(rawRow(ALICE).get("first_name")).isEqualTo("Edited");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM user_info", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    // ---- real HTTP: filter, actuator, path tricks ------------------------------------------------------------------

    @Test
    void realHttpWithoutHeaderIs401JsonWithBearerChallenge() throws Exception {
        HttpResponse<String> res = http("/user/v1/me");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        JsonNode body = mapper.readTree(res.body());
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("path").asText()).isEqualTo("/user/v1/me");
    }

    @Test
    void actuatorHealthIsPublicAndOnlyHealthIsExposed() throws Exception {
        HttpResponse<String> health = http("/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(health.body()).get("status").asText()).isEqualTo("UP");
        for (String hidden : List.of("/actuator/env", "/actuator/beans", "/actuator/mappings", "/actuator/heapdump")) {
            assertThat(http(hidden).statusCode()).as(hidden).isEqualTo(404);
        }
    }

    @Test
    void oldEndpointsAreGoneOverRealHttp() throws Exception {
        saved(ALICE, "Alice", "Anderson");
        HttpRequest post = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/user/v1/createUpdate"))
                .header("X-User-Id", ALICE).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"user_id\":\"" + BOB + "\"}")).build();
        assertThat(HttpClient.newHttpClient().send(post, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(404);
        HttpRequest getUser = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/user/v1/getUser"))
                .header("X-User-Id", ALICE).GET().build();
        assertThat(HttpClient.newHttpClient().send(getUser, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(404);
    }

    @Test
    void dotDotSegmentsCannotSmuggleARequestPastTheIdentityFilterViaTheActuatorPrefix() throws Exception {
        String response = raw("GET /actuator/../user/v1/me HTTP/1.1");
        assertThat(response).startsWith("HTTP/1.1 401");
        assertThat(response).doesNotContain("first_name");
        String semicolon = raw("GET /actuator;x=y/../user/v1/me HTTP/1.1");
        assertThat(semicolon).doesNotStartWith("HTTP/1.1 200");
        String error = raw("GET /error/../user/v1/me HTTP/1.1");
        assertThat(error).startsWith("HTTP/1.1 401");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE"})
    void aDirectRequestToErrorWithoutTheHeaderIsThePinned401OverRealHttp(String method) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error"))
                .method(method, "GET".equals(method) || "DELETE".equals(method)
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json").build();
        HttpResponse<String> res = http11.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        JsonNode body = mapper.readTree(res.body());
        assertThat(body.fieldNames()).toIterable()
                .containsExactly("timestamp", "status", "error", "code", "message", "path");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("path").asText()).isEqualTo("/error");
    }

    @Test
    void aDirectRequestToErrorWithAMalformedHeaderIsAlso401() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error"))
                .header("X-User-Id", "not-a-uuid").GET().build();
        HttpResponse<String> res = http11.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(mapper.readTree(res.body()).get("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void theContainersInternalErrorDispatchStillProducesThePinnedBodyForAnExemptActuatorPath() throws Exception {
        // /actuator/env is exempt from the header check and does not exist, so the container dispatches the 404 to
        // /error internally, without any X-User-Id. That dispatch must reach the pinned error body, not a 401.
        HttpResponse<String> res = http("/actuator/env");
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.headers().firstValue("WWW-Authenticate")).isEmpty();
        JsonNode body = mapper.readTree(res.body());
        assertThat(body.fieldNames()).toIterable()
                .containsExactly("timestamp", "status", "error", "code", "message", "path");
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.get("path").asText()).isEqualTo("/actuator/env");
    }

    private HttpResponse<String> http(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String raw(String requestLine) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((requestLine + "\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
