package org.example.Auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.Api.ApiError;
import org.example.Api.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 401 for a missing, malformed, invalid or expired bearer token: pinned JSON body plus WWW-Authenticate: Bearer. */
@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        ApiError.write(objectMapper, request, response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                "Authentication is required: missing, invalid or expired bearer token");
    }
}
