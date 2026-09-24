package org.example.Auth;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Stateless resource server: bearer JWTs are verified with the public key derived from the signing key. There is
 * no HTTP Basic, no form login and no session.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /** Endpoints that need no token. Exact paths; the HTTP method is pinned where it applies. */
    static final RequestMatcher PUBLIC_ENDPOINTS = new OrRequestMatcher(
            PATHS.matcher(HttpMethod.POST, "/auth/v1/otp/request"),
            PATHS.matcher(HttpMethod.POST, "/auth/v1/otp/verify"),
            PATHS.matcher(HttpMethod.POST, "/auth/v1/refreshToken"),
            PATHS.matcher(HttpMethod.POST, "/auth/v1/logout"),
            PATHS.matcher(HttpMethod.GET, "/auth/v1/.well-known/jwks.json"),
            PATHS.matcher(HttpMethod.GET, "/health"),
            PATHS.matcher(HttpMethod.GET, "/actuator/health/**"));

    /**
     * Boot's error dispatch: when the container forwards a failed request to {@code /error}, that inner dispatch has
     * to get through so that the error can be rendered. Only the ERROR dispatcher type qualifies; there is no
     * path-based permit for {@code /error}, so a client that calls {@code GET /error} or {@code POST /error}
     * directly (a normal REQUEST dispatch) is an ordinary unauthenticated request and gets the pinned 401.
     */
    static final RequestMatcher ERROR_DISPATCH = request -> request.getDispatcherType() == DispatcherType.ERROR;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                                   JsonAuthenticationEntryPoint entryPoint,
                                                   JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        BearerTokenResolver bearerTokenResolver = publicEndpointsIgnoreBearerTokens(new DefaultBearerTokenResolver());

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(ERROR_DISPATCH).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Public endpoints ignore the Authorization header completely. Without this the resource-server filter would
     * authenticate the header BEFORE the permitAll rule is consulted, so a stale, expired or garbage bearer token
     * would turn a public call into a 401 (the mobile client's interceptor attaches its expired access token to the
     * refresh call, which is exactly the call that has to work then). For every other request the default resolver
     * applies, so a bad token on a protected path is still rejected.
     */
    public static BearerTokenResolver publicEndpointsIgnoreBearerTokens(BearerTokenResolver delegate) {
        return request -> PUBLIC_ENDPOINTS.matches(request) ? null : delegate.resolve(request);
    }

    /** The {@code roles} claim becomes the authorities verbatim (ROLE_USER stays ROLE_USER: no prefix is added). */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

}

