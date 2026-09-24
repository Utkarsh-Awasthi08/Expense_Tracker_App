package com.expense.expenseService;

import com.expense.expenseService.Entities.Expense;
import com.expense.expenseService.Repository.ExpenseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end through a real Tomcat, the real filter chain, Spring Data and a real MySQL 8.4 (Flyway + validate).
 * Skipped (not failed) when Docker is unavailable; Kafka listeners are off, no broker is needed.
 * <p>
 * Not @Transactional: requests run on server threads and must really commit.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=false")
class ExpenseApiIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * Fails one probe path at container level: a filter ahead of the identity filter that calls sendError, which makes
     * Tomcat dispatch to /error for a request the identity filter has not seen.
     */
    @TestConfiguration
    static class ContainerErrorProbeConfig {
        @Bean
        FilterRegistrationBean<ContainerErrorProbe> containerErrorProbe() {
            FilterRegistrationBean<ContainerErrorProbe> registration = new FilterRegistrationBean<>(new ContainerErrorProbe());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    static class ContainerErrorProbe extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ("/__probe/container-error".equals(request.getRequestURI())) {
                response.sendError(400);
                return;
            }
            chain.doFilter(request, response);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ExpenseRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final String userA = UUID.randomUUID().toString();
    private final String userB = UUID.randomUUID().toString();

    @BeforeEach
    void cleanTable() {
        repository.deleteAll();
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    record Reply(int status, String body, HttpResponse<String> raw) {
        JsonNode json(ObjectMapper mapper) {
            try {
                return mapper.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("not JSON: " + body, e);
            }
        }
    }

    private Reply get(String path, String userId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        return send(request.build());
    }

    private Reply post(String path, String body, String userId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        return send(request.build());
    }

    private Reply postAs(String path, String contentType, String body, String userId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        return send(request.build());
    }

    private Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body(), response);
    }

    private JsonNode ok(Reply reply) {
        assertEquals(200, reply.status(), reply.body());
        return reply.json(json);
    }

    private Expense seed(String userId, String merchant, String amount, LocalDate txnDate, Instant smsReceivedAt) {
        Expense e = new Expense();
        e.setExternalId(UUID.randomUUID().toString());
        e.setUserId(userId);
        e.setSmsHash(smsReceivedAt == null ? null : UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000");
        e.setSmsReceivedAt(smsReceivedAt);
        e.setAmount(new BigDecimal(amount));
        e.setMerchant(merchant);
        e.setCategory("FOOD");
        e.setTxnType("DEBIT");
        e.setAccountLast4("4321");
        e.setTxnDate(txnDate);
        e.setCreatedAt(Instant.parse("2026-06-01T10:00:00Z"));
        return repository.save(e);
    }

    private static List<String> externalIds(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(i -> ids.add(i.get("external_id").asText()));
        return ids;
    }

    private static List<String> merchants(JsonNode items) {
        List<String> names = new ArrayList<>();
        items.forEach(i -> names.add(i.get("merchant").asText()));
        return names;
    }

    private String rawHttp(String requestLine) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((requestLine + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    // ---- identity over real HTTP --------------------------------------------------------------------------

    @Test
    void actuatorHealthIsOpenWithoutAnIdentityAndReportsUp() throws Exception {
        Reply reply = get("/actuator/health", null);

        assertEquals(200, reply.status(), reply.body());
        assertEquals("UP", reply.json(json).get("status").asText());
    }

    @Test
    void everyBusinessPathWithoutAValidIdentityIs401WithThePinnedBody() throws Exception {
        seed(userA, "A-only", "10", LocalDate.of(2026, 1, 1), null);

        for (String path : List.of("/expense/v1/expenses", "/expense/v1/getExpense", "/expense/v1/expenses?user_id=" + userA,
                "/getExpense?user_id=" + userA, "/anything/else")) {
            Reply noHeader = get(path, null);
            Reply badHeader = get(path, "not-a-uuid");

            for (Reply reply : List.of(noHeader, badHeader)) {
                assertEquals(401, reply.status(), path + " -> " + reply.body());
                assertEquals("Bearer", reply.raw().headers().firstValue("WWW-Authenticate").orElse(null), path);
                assertTrue(reply.raw().headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
                JsonNode body = reply.json(json);
                assertEquals("UNAUTHORIZED", body.get("code").asText());
                assertEquals(401, body.get("status").asInt());
                assertEquals("Unauthorized", body.get("error").asText());
                assertTrue(body.get("timestamp").asText().endsWith("Z"));
                assertFalse(reply.body().contains("A-only"), "no data may leak into a 401");
            }
        }
    }

    @Test
    void duplicatedIdentityHeaderIs401OverTheWire() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/expense/v1/expenses"))
                .header("X-User-Id", userA).header("X-User-Id", userB).GET().build();

        assertEquals(401, send(request).status());
    }

    @Test
    void aDotDotDetourThroughTheOpenActuatorPathDoesNotSkipTheIdentityCheck() throws Exception {
        seed(userA, "A-only", "10", LocalDate.of(2026, 1, 1), null);

        String response = rawHttp("GET /actuator/../expense/v1/expenses");

        String statusLine = response.lines().findFirst().orElse("");
        assertFalse(statusLine.contains(" 200 "), statusLine);
        assertFalse(response.contains("A-only"), response);
        assertTrue(statusLine.contains(" 401 ") || statusLine.contains(" 400 ") || statusLine.contains(" 404 "), statusLine);
    }

    @Test
    void aDirectRequestForErrorWithoutAnIdentityIsThePinned401NotA500() throws Exception {
        for (Reply reply : List.of(get("/error", null), get("/error", "not-a-uuid"),
                post("/error", "{}", null), get("/error?x=1", null))) {
            assertEquals(401, reply.status(), reply.body());
            assertEquals("Bearer", reply.raw().headers().firstValue("WWW-Authenticate").orElse(null));
            assertTrue(reply.raw().headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            JsonNode body = reply.json(json);
            assertEquals("UNAUTHORIZED", body.get("code").asText());
            assertEquals(401, body.get("status").asInt());
            assertEquals("Unauthorized", body.get("error").asText());
            assertTrue(body.get("path").asText().startsWith("/error"), body.toString());
            assertEquals(6, body.size(), body.toString());
        }
    }

    @Test
    void aDirectRequestForErrorWithAnIdentityIsJustANotFoundInThePinnedShape() throws Exception {
        Reply reply = get("/error", userA);

        assertEquals(404, reply.status(), reply.body());
        JsonNode body = reply.json(json);
        assertEquals("NOT_FOUND", body.get("code").asText());
        assertEquals("/error", body.get("path").asText());
    }

    @Test
    void aContainerErrorDispatchStillRendersThePinnedBodyWithoutAnIdentity() throws Exception {
        // ContainerErrorProbe answers /__probe/container-error with response.sendError(400) BEFORE the identity filter
        // runs, so Tomcat makes an ERROR dispatch to /error for a request the identity filter never saw. That dispatch
        // must skip the identity filter (OncePerRequestFilter does not filter ERROR dispatches); with the old /error
        // path exemption gone it is the only thing keeping this a 400 instead of a 401.
        Reply reply = get("/__probe/container-error", null);

        assertEquals(400, reply.status(), reply.body());
        assertTrue(reply.raw().headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(reply.raw().headers().firstValue("WWW-Authenticate").isEmpty());
        JsonNode body = reply.json(json);
        assertEquals("BAD_REQUEST", body.get("code").asText());
        assertEquals(400, body.get("status").asInt());
        assertEquals("/__probe/container-error", body.get("path").asText(), "the ORIGINAL path, not /error");
        assertEquals(6, body.size(), body.toString());
    }

    @Test
    void theOldUnprefixedEndpointsAreGoneOverTheWire() throws Exception {
        seed(userA, "A-only", "10", LocalDate.of(2026, 1, 1), null);

        Reply oldGet = get("/getExpense?user_id=" + userA, userA);
        Reply oldPost = post("/addExpense", "{\"amount\": 5}", userA);

        assertEquals(404, oldGet.status(), oldGet.body());
        assertEquals("NOT_FOUND", oldGet.json(json).get("code").asText());
        assertEquals(404, oldPost.status(), oldPost.body());
        assertFalse(oldGet.body().contains("A-only"));
        assertEquals(0, jdbc.queryForObject("select count(*) from expense where merchant is null", Integer.class));
    }

    // ---- list ---------------------------------------------------------------------------------------------

    @Test
    void fromAndToAreInclusiveOnTxnDate() throws Exception {
        seed(userA, "jan01", "1", LocalDate.of(2026, 1, 1), null);
        seed(userA, "jan15", "2", LocalDate.of(2026, 1, 15), null);
        seed(userA, "jan31", "3", LocalDate.of(2026, 1, 31), null);
        seed(userA, "feb01", "4", LocalDate.of(2026, 2, 1), null);
        seed(userA, "dec31", "5", LocalDate.of(2025, 12, 31), null);

        assertEquals(List.of("jan31", "jan15", "jan01"),
                merchants(ok(get("/expense/v1/expenses?from=2026-01-01&to=2026-01-31", userA)).get("items")),
                "both bounds are inclusive");
        assertEquals(List.of("feb01", "jan31", "jan15"),
                merchants(ok(get("/expense/v1/expenses?from=2026-01-15", userA)).get("items")));
        assertEquals(List.of("jan15", "jan01", "dec31"),
                merchants(ok(get("/expense/v1/expenses?to=2026-01-15", userA)).get("items")));
        assertEquals(List.of("jan15"),
                merchants(ok(get("/expense/v1/expenses?from=2026-01-15&to=2026-01-15", userA)).get("items")),
                "from == to selects exactly that day");
        assertEquals(List.of("feb01", "jan31", "jan15", "jan01", "dec31"),
                merchants(ok(get("/expense/v1/expenses", userA)).get("items")));

        JsonNode none = ok(get("/expense/v1/expenses?from=2030-01-01&to=2030-12-31", userA));
        assertEquals(0, none.get("items").size());
        assertEquals(0, none.get("total_elements").asInt());
        assertEquals(0, none.get("total_pages").asInt());
        assertEquals(0, none.get("page").asInt());
        assertEquals(20, none.get("size").asInt());
    }

    @Test
    void orderingIsTxnDateDescThenIdDesc() throws Exception {
        seed(userA, "old", "1", LocalDate.of(2026, 1, 1), null);
        seed(userA, "same-day-first", "2", LocalDate.of(2026, 3, 1), null);
        seed(userA, "same-day-second", "3", LocalDate.of(2026, 3, 1), null);
        seed(userA, "same-day-third", "4", LocalDate.of(2026, 3, 1), null);
        seed(userA, "newest-but-inserted-last", "5", LocalDate.of(2026, 4, 1), null);

        assertEquals(List.of("newest-but-inserted-last", "same-day-third", "same-day-second", "same-day-first", "old"),
                merchants(ok(get("/expense/v1/expenses", userA)).get("items")));
    }

    @Test
    void paginationTotalsAndPagesAreConsistentAcrossTheWholeSet() throws Exception {
        List<Expense> all = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            all.add(seed(userA, "m" + i, "1." + (i % 10), LocalDate.of(2026, 1, 1).plusDays(i % 9), null));
        }
        seed(userB, "someone-else", "9", LocalDate.of(2026, 1, 1), null);
        List<String> expectedOrder = all.stream()
                .sorted(Comparator.comparing(Expense::getTxnDate).thenComparing(Expense::getId).reversed())
                .map(Expense::getExternalId).toList();

        List<String> collected = new ArrayList<>();
        int[] expectedSizes = {20, 20, 5, 0};
        for (int page = 0; page < 4; page++) {
            JsonNode body = ok(get("/expense/v1/expenses?page=" + page + "&size=20", userA));
            assertEquals(page, body.get("page").asInt());
            assertEquals(20, body.get("size").asInt());
            assertEquals(45, body.get("total_elements").asLong(), "user B's row must not be counted");
            assertEquals(3, body.get("total_pages").asInt());
            assertEquals(expectedSizes[page], body.get("items").size(), "page " + page);
            collected.addAll(externalIds(body.get("items")));
        }
        assertEquals(expectedOrder, collected, "pages concatenate to the full, strictly ordered set");
        assertEquals(45, new HashSet<>(collected).size(), "no row repeated or skipped across pages");

        JsonNode big = ok(get("/expense/v1/expenses?size=200", userA));
        assertEquals(45, big.get("items").size());
        assertEquals(1, big.get("total_pages").asInt());
        assertEquals(200, big.get("size").asInt());

        JsonNode defaults = ok(get("/expense/v1/expenses", userA));
        assertEquals(20, defaults.get("items").size());
        assertEquals(0, defaults.get("page").asInt());
    }

    @Test
    void sizeAboveTwoHundredIs400ValidationFailedOverTheWire() throws Exception {
        Reply reply = get("/expense/v1/expenses?size=201", userA);

        assertEquals(400, reply.status(), reply.body());
        JsonNode body = reply.json(json);
        assertEquals("VALIDATION_FAILED", body.get("code").asText());
        assertEquals("size", body.get("details").get(0).get("field").asText());
    }

    @Test
    void listItemsCarryExactlyTheContractFieldsWithNumericAmountsAndNoUserIdOrSmsHash() throws Exception {
        seed(userA, "Blue Tokai", "1234.5", LocalDate.of(2026, 3, 13), Instant.parse("2026-03-14T08:15:30.123456Z"));

        Reply reply = get("/expense/v1/expenses", userA);
        JsonNode item = ok(reply).get("items").get(0);

        assertEquals(9, item.size(), item.toString());
        assertTrue(item.get("amount").isNumber());
        assertEquals(0, new BigDecimal("1234.50").compareTo(item.get("amount").decimalValue()));
        assertTrue(reply.body().contains("\"amount\":1234.50"), reply.body());
        assertEquals("INR", item.get("currency").asText());
        assertEquals("Blue Tokai", item.get("merchant").asText());
        assertEquals("FOOD", item.get("category").asText());
        assertEquals("DEBIT", item.get("txn_type").asText());
        assertEquals("4321", item.get("account_last4").asText());
        assertEquals("2026-03-13", item.get("txn_date").asText());
        assertEquals(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse(item.get("created_at").asText()),
                "created_at on the list is the row's created_at, not the SMS time");
        assertFalse(reply.body().contains("user_id") || reply.body().contains("sms_hash"));
        assertFalse(reply.body().contains(userA));
    }

    // ---- isolation ----------------------------------------------------------------------------------------

    @Test
    void oneUserNeverSeesAnotherUsersRowsViaListOrAlias() throws Exception {
        for (int i = 0; i < 3; i++) {
            seed(userA, "A-" + i, "10", LocalDate.of(2026, 1, 1).plusDays(i), null);
        }
        for (int i = 0; i < 4; i++) {
            seed(userB, "B-" + i, "20", LocalDate.of(2026, 1, 1).plusDays(i), null);
        }

        JsonNode listA = ok(get("/expense/v1/expenses?size=200", userA));
        JsonNode listB = ok(get("/expense/v1/expenses?size=200", userB));
        JsonNode aliasA = ok(get("/expense/v1/getExpense", userA));
        JsonNode aliasB = ok(get("/expense/v1/getExpense", userB));

        assertEquals(3, listA.get("total_elements").asInt());
        assertEquals(4, listB.get("total_elements").asInt());
        assertTrue(merchants(listA.get("items")).stream().allMatch(m -> m.startsWith("A-")));
        assertTrue(merchants(listB.get("items")).stream().allMatch(m -> m.startsWith("B-")));
        assertEquals(3, aliasA.size());
        assertEquals(4, aliasB.size());
        for (JsonNode row : aliasA) {
            assertTrue(row.get("merchant").asText().startsWith("A-"));
        }
        for (JsonNode row : aliasB) {
            assertTrue(row.get("merchant").asText().startsWith("B-"));
        }
        assertTrue(Set.copyOf(externalIds(listA.get("items"))).stream()
                .noneMatch(externalIds(listB.get("items"))::contains));
    }

    @Test
    void aUserIdInTheQueryStringNeverSwitchesTheOwner() throws Exception {
        seed(userA, "A-secret", "10", LocalDate.of(2026, 1, 1), null);
        seed(userB, "B-secret", "20", LocalDate.of(2026, 1, 1), null);

        for (String path : List.of("/expense/v1/expenses?user_id=" + userA, "/expense/v1/expenses?userId=" + userA,
                "/expense/v1/getExpense?user_id=" + userA, "/expense/v1/getExpense?userId=" + userA)) {
            Reply asB = get(path, userB);
            assertEquals(200, asB.status(), path);
            assertTrue(asB.body().contains("B-secret"), path);
            assertFalse(asB.body().contains("A-secret"), path + " leaked another user's row");
        }
    }

    @Test
    void aBrandNewUserSeesEmptyResultsNotAnError() throws Exception {
        seed(userA, "A-only", "10", LocalDate.of(2026, 1, 1), null);
        String newcomer = UUID.randomUUID().toString();

        JsonNode list = ok(get("/expense/v1/expenses", newcomer));
        Reply alias = get("/expense/v1/getExpense", newcomer);

        assertEquals(0, list.get("items").size());
        assertEquals(0, list.get("total_elements").asInt());
        assertEquals(200, alias.status());
        assertEquals("[]", alias.body());
    }

    @Test
    void postAlwaysStoresTheRowForTheHeaderUserNeverForTheUserInTheBody() throws Exception {
        seed(userB, "B-existing", "20", LocalDate.of(2026, 1, 1), null);
        int bBefore = ok(get("/expense/v1/expenses", userB)).get("total_elements").asInt();

        Reply created = post("/expense/v1/expenses", """
                {"amount": 15.5, "merchant": "Mallory", "user_id": "%s", "userId": "%s"}""".formatted(userB, userB), userA);

        assertEquals(201, created.status(), created.body());
        String externalId = created.json(json).get("external_id").asText();
        assertEquals(userA, jdbc.queryForObject("select user_id from expense where external_id = ?", String.class, externalId));

        JsonNode listB = ok(get("/expense/v1/expenses?size=200", userB));
        assertEquals(bBefore, listB.get("total_elements").asInt(), "B's data is untouched");
        assertFalse(externalIds(listB.get("items")).contains(externalId));
        assertFalse(ok(get("/expense/v1/getExpense", userB)).toString().contains("Mallory"));
        assertTrue(externalIds(ok(get("/expense/v1/expenses", userA)).get("items")).contains(externalId));
    }

    // ---- create -------------------------------------------------------------------------------------------

    @Test
    void postIgnoresAUserIdInTheQueryStringAndStoresTheRowForTheCallerOnly() throws Exception {
        seed(userB, "B-existing", "20", LocalDate.of(2026, 1, 1), null);
        int bBefore = ok(get("/expense/v1/expenses", userB)).get("total_elements").asInt();
        List<String> created = new ArrayList<>();

        for (String parameter : List.of("user_id", "userId", "X-User-Id", "owner")) {
            Reply reply = post("/expense/v1/expenses?" + parameter + "=" + userB,
                    "{\"amount\": 15.5, \"merchant\": \"Query forger\"}", userA);
            assertEquals(201, reply.status(), parameter + " -> " + reply.body());
            created.add(reply.json(json).get("external_id").asText());
        }
        // the forged id in the query string AND in the body together
        Reply both = post("/expense/v1/expenses?user_id=" + userB,
                "{\"amount\": 15.5, \"merchant\": \"Both forger\", \"user_id\": \"" + userB + "\"}", userA);
        assertEquals(201, both.status(), both.body());
        created.add(both.json(json).get("external_id").asText());

        for (String externalId : created) {
            assertEquals(userA, jdbc.queryForObject("select user_id from expense where external_id = ?", String.class, externalId));
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from expense where user_id = ? and merchant like '%forger'",
                Integer.class, userB), "nothing was stored for the user named in the query string");
        assertEquals(5, jdbc.queryForObject("select count(*) from expense where user_id = ? and merchant like '%forger'",
                Integer.class, userA));
        JsonNode listB = ok(get("/expense/v1/expenses?size=200", userB));
        assertEquals(bBefore, listB.get("total_elements").asInt(), "B's data is untouched");
        assertFalse(ok(get("/expense/v1/getExpense", userB)).toString().contains("forger"));
    }

    @Test
    void aFormEncodedUserIdCannotSetTheOwnerOrCreateARow() throws Exception {
        String form = "user_id=" + userB + "&userId=" + userB + "&amount=15.5&merchant=Form+forger";

        Reply plain = postAs("/expense/v1/expenses", "application/x-www-form-urlencoded", form, userA);
        Reply withQuery = postAs("/expense/v1/expenses?user_id=" + userB, "application/x-www-form-urlencoded", form, userA);

        for (Reply reply : List.of(plain, withQuery)) {
            assertEquals(415, reply.status(), reply.body());
            assertEquals("BAD_REQUEST", reply.json(json).get("code").asText());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from expense", Integer.class),
                "a form post stores nothing for anyone");
    }

    @Test
    void trailingContentAfterTheJsonObjectIs400BadRequestAndStoresNothing() throws Exception {
        for (String body : List.of("{\"amount\":1}garbage", "{\"amount\": 1} garbage", "{\"amount\":1}{\"amount\":2}",
                "{\"amount\":1}\n{\"amount\":2}", "{\"amount\":1}}", "{\"amount\":1}[]", "{\"amount\":1},")) {
            Reply reply = post("/expense/v1/expenses", body, userA);

            assertEquals(400, reply.status(), body + " -> " + reply.body());
            JsonNode error = reply.json(json);
            assertEquals("BAD_REQUEST", error.get("code").asText(), body);
            assertEquals("/expense/v1/expenses", error.get("path").asText());
            assertFalse(reply.body().contains("garbage"), "the rejected input is never echoed");
        }

        assertEquals(0, jdbc.queryForObject("select count(*) from expense", Integer.class));
        // trailing whitespace is not content
        assertEquals(201, post("/expense/v1/expenses", "{\"amount\":1}\n  \r\n", userA).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from expense", Integer.class));
    }


    @Test
    void postPersistsTheRoundedAmountDefaultsAndTodayInUtc() throws Exception {
        LocalDate before = LocalDate.now(ZoneOffset.UTC);

        Reply created = post("/expense/v1/expenses", "{\"amount\": 12.345, \"merchant\": \"  Chai Point \"}", userA);

        LocalDate after = LocalDate.now(ZoneOffset.UTC);
        assertEquals(201, created.status(), created.body());
        JsonNode item = created.json(json);
        assertEquals(9, item.size());
        assertEquals(0, new BigDecimal("12.35").compareTo(item.get("amount").decimalValue()), "HALF_UP to 2 decimals");
        assertTrue(created.body().contains("\"amount\":12.35"), created.body());
        assertEquals("Chai Point", item.get("merchant").asText());
        assertEquals("INR", item.get("currency").asText());
        assertEquals("OTHER", item.get("category").asText());
        assertEquals("DEBIT", item.get("txn_type").asText());
        assertTrue(item.get("account_last4").isNull());

        var row = jdbc.queryForMap("""
                select user_id, cast(amount as char) as amount, cast(txn_date as char) as txn_date, sms_hash, sms_received_at,
                       created_at, updated_at
                from expense where external_id = ?""", item.get("external_id").asText());
        assertEquals(userA, row.get("user_id"));
        assertEquals("12.35", row.get("amount"));
        LocalDate txnDate = LocalDate.parse((String) row.get("txn_date"));
        assertTrue(!txnDate.isBefore(before) && !txnDate.isAfter(after), "txn_date defaults to today (UTC): " + txnDate);
        assertNull(row.get("sms_hash"));
        assertNull(row.get("sms_received_at"));
        assertNotNull(row.get("created_at"));
        assertNotNull(row.get("updated_at"));
    }

    @Test
    void postIgnoresEveryServerOwnedFieldInTheBody() throws Exception {
        String forgedExternalId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        String forgedHash = "f".repeat(64);

        Reply created = post("/expense/v1/expenses", """
                {"amount": 5, "external_id": "%s", "created_at": "1999-01-01T00:00:00Z", "sms_hash": "%s",
                 "sms_received_at": "1999-01-01T00:00:00Z", "id": 1, "updated_at": "1999-01-01T00:00:00Z",
                 "account_last4": "0000"}""".formatted(forgedExternalId, forgedHash), userA);

        assertEquals(201, created.status(), created.body());
        String externalId = created.json(json).get("external_id").asText();
        assertNotEquals(forgedExternalId, externalId);
        var row = jdbc.queryForMap("select sms_hash, sms_received_at, account_last4, created_at from expense where external_id = ?",
                externalId);
        assertNull(row.get("sms_hash"));
        assertNull(row.get("sms_received_at"));
        assertNull(row.get("account_last4"));
        assertTrue(Instant.parse(created.json(json).get("created_at").asText()).isAfter(Instant.parse("2020-01-01T00:00:00Z")));
        assertEquals(0, jdbc.queryForObject("select count(*) from expense where external_id = ?", Integer.class, forgedExternalId));
        assertEquals(0, jdbc.queryForObject("select count(*) from expense where sms_hash = ?", Integer.class, forgedHash));
    }

    @Test
    void invalidPostsAreRejectedAndStoreNothing() throws Exception {
        for (String body : List.of("{}", "{\"amount\": 0}", "{\"amount\": -1}", "{\"amount\": 1, \"currency\": \"XYZ\"}",
                "{\"amount\": 1, \"category\": \"food\"}", "{\"amount\": 1, \"txn_type\": \"REFUND\"}",
                "{\"amount\": 100000000000000000}")) {
            Reply reply = post("/expense/v1/expenses", body, userA);
            assertEquals(400, reply.status(), body + " -> " + reply.body());
            assertEquals("VALIDATION_FAILED", reply.json(json).get("code").asText(), body);
        }
        Reply malformed = post("/expense/v1/expenses", "{\"amount\": 1", userA);
        assertEquals(400, malformed.status());
        assertEquals("BAD_REQUEST", malformed.json(json).get("code").asText());

        assertEquals(0, jdbc.queryForObject("select count(*) from expense", Integer.class));
    }

    @Test
    void absurdAmountLiteralsAreRejectedInUnderASecondThroughTheRealEndpoint() throws Exception {
        // warm-up so class loading / connection setup is not charged to the timed requests
        assertEquals(201, post("/expense/v1/expenses", "{\"amount\": 1}", userA).status());
        int rowsBefore = jdbc.queryForObject("select count(*) from expense", Integer.class);

        for (String literal : List.of("1e999999999", "1E+50000000", "1e-999999999", "1E-50000000", "9.9e2147483647")) {
            long start = System.nanoTime();
            Reply reply = post("/expense/v1/expenses", "{\"amount\": " + literal + "}", userA);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertEquals(400, reply.status(), literal + " -> " + reply.body());
            assertEquals("VALIDATION_FAILED", reply.json(json).get("code").asText());
            assertTrue(took.compareTo(Duration.ofSeconds(1)) < 0, literal + " took " + took);
        }

        assertEquals(rowsBefore, jdbc.queryForObject("select count(*) from expense", Integer.class));
        assertEquals(200, get("/expense/v1/expenses", userA).status(), "the service is still healthy afterwards");
    }

    // ---- legacy alias -------------------------------------------------------------------------------------

    @Test
    void aliasIsABareArrayWithNumericAmountAndCoalescedCreatedAt() throws Exception {
        seed(userA, "from-sms", "1234.5", LocalDate.of(2026, 3, 14), Instant.parse("2026-03-14T08:15:30.250Z"));
        seed(userA, "manual", "20", LocalDate.of(2026, 3, 13), null);

        Reply reply = get("/expense/v1/getExpense", userA);

        assertEquals(200, reply.status());
        assertTrue(reply.body().startsWith("[") && reply.body().endsWith("]"), "bare array: " + reply.body());
        JsonNode rows = reply.json(json);
        assertTrue(rows.isArray());
        assertEquals(2, rows.size());
        JsonNode first = rows.get(0);
        JsonNode second = rows.get(1);
        assertEquals(4, first.size(), first.toString());
        assertEquals("from-sms", first.get("merchant").asText());
        assertTrue(first.get("amount").isNumber(), "the mobile app calls amount.toFixed(2)");
        assertEquals(0, new BigDecimal("1234.50").compareTo(first.get("amount").decimalValue()));
        assertEquals("INR", first.get("currency").asText());
        assertEquals(Instant.parse("2026-03-14T08:15:30.250Z"), Instant.parse(first.get("created_at").asText()),
                "created_at = sms_received_at when present");
        assertEquals("manual", second.get("merchant").asText());
        assertEquals(Instant.parse("2026-06-01T10:00:00Z"), Instant.parse(second.get("created_at").asText()),
                "created_at falls back to the row's created_at");
        assertFalse(reply.body().contains("user_id") || reply.body().contains("sms_hash") || reply.body().contains("external_id"));
    }

    @Test
    void aliasReturnsAtMostTheNewestFiveHundredRows() throws Exception {
        List<Expense> rows = new ArrayList<>();
        for (int i = 0; i < 505; i++) {
            rows.add(seed(userA, "r" + i, "1", LocalDate.of(2025, 1, 1).plusDays(i % 30), null));
        }
        seed(userB, "someone-else", "9", LocalDate.of(2030, 1, 1), null);
        List<String> expected = rows.stream()
                .sorted(Comparator.comparing(Expense::getTxnDate).thenComparing(Expense::getId).reversed())
                .limit(500).map(Expense::getMerchant).toList();

        JsonNode alias = ok(get("/expense/v1/getExpense", userA));

        assertEquals(500, alias.size());
        assertEquals(expected, merchants(alias));
    }
}
