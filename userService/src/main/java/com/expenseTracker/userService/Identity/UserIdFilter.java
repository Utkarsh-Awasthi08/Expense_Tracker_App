package com.expenseTracker.userService.Identity;

import com.expenseTracker.userService.Error.ErrorBody;
import com.expenseTracker.userService.Error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identity comes only from the X-User-Id header the gateway sets from the verified JWT. Every request except
 * /actuator/** must carry exactly one header value that is a UUID; anything else is a 401 with the pinned error
 * body. There is deliberately no /error exemption: the container's own ERROR dispatches to /error are skipped by
 * {@link OncePerRequestFilter} itself, so a direct client request to /error is just another unauthenticated
 * request. The (lower-cased) id is exposed to controllers as a request attribute.
 * Services trust this header only because their ports are not published; see docs/CONTRACTS.md.
 */
@Component
public class UserIdFilter extends OncePerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";

    /** Request attribute holding the validated user id (a compile-time constant so it can be used in annotations). */
    public static final String USER_ID_ATTRIBUTE = "com.expenseTracker.userService.Identity.UserIdFilter.USER_ID";

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ObjectMapper objectMapper;

    public UserIdFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isUnder(resolvedPath(request), "/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(USER_ID_HEADER));
        if (values.size() != 1 || values.get(0) == null || !UUID_PATTERN.matcher(values.get(0)).matches()) {
            reject(request, response);
            return;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, values.get(0).toLowerCase(Locale.ROOT));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorBody.of(
                HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                "Missing or invalid " + USER_ID_HEADER + " header", request.getRequestURI()));
    }

    /**
     * The decoded, normalized path the dispatcher will route on (servlet path + path info), not the raw
     * request URI: {@code /actuator/../user/v1/me} must be judged as {@code /user/v1/me}.
     */
    private static String resolvedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        return servletPath + pathInfo;
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
