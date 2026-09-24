package com.expense.expenseService.Identity;

import com.expense.expenseService.Web.ApiError;
import com.expense.expenseService.Web.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Requires a strict-UUID {@code X-User-Id} (exactly one value) on every request except {@code /actuator/**},
 * and exposes it to controllers through {@link CurrentUserId}.
 * <p>
 * {@code /error} is deliberately NOT exempt by path: the container's own error dispatches (an undecodable URL, a
 * {@code sendError}) are skipped because {@link OncePerRequestFilter} does not filter {@code ERROR} dispatches, while a
 * client that requests {@code /error} directly is an ordinary request and gets the pinned 401 without an identity.
 * <p>
 * The gateway sets this header from the verified JWT and strips any inbound copy. This service trusts it only
 * because its port is not published; the filter therefore also refuses anything that is not a plain UUID
 * (blank, duplicated, comma-joined, padded) so a malformed value can never reach a query.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class UserIdentityFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ID_ATTRIBUTE = UserIdentityFilter.class.getName() + ".USER_ID";

    private static final Logger log = LoggerFactory.getLogger(UserIdentityFilter.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final String INVALID_IDENTITY_MESSAGE = "Missing or invalid X-User-Id header";

    private final ObjectMapper objectMapper;

    public UserIdentityFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isOpenPath(pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(USER_ID_HEADER));
        if (values.size() != 1 || !UUID_PATTERN.matcher(values.get(0)).matches()) {
            // never log the header value: it is attacker-controlled
            log.debug("Rejected request without a valid {} header ({} value(s))", USER_ID_HEADER, values.size());
            reject(request, response);
            return;
        }
        // A UUID is case-insensitive; one canonical spelling keeps every query and log line consistent.
        request.setAttribute(USER_ID_ATTRIBUTE, values.get(0).toLowerCase(Locale.ROOT));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiError body = ApiError.of(HttpStatus.UNAUTHORIZED.value(), ErrorCode.UNAUTHORIZED,
                INVALID_IDENTITY_MESSAGE, request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /**
     * The path Spring will route on: decoded, without the context path and {@code ;params}, and with dot segments
     * resolved, so {@code /actuator/../expense/v1/expenses} is judged as the protected path it really is.
     */
    static String pathOf(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return StringUtils.cleanPath(path);
    }

    static boolean isOpenPath(String path) {
        return "/actuator".equals(path)
                || path.startsWith("/actuator/");
    }
}
