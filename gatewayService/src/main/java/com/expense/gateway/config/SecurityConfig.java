package com.expense.gateway.config;

import com.expense.gateway.security.JsonAccessDeniedHandler;
import com.expense.gateway.security.JsonAuthenticationEntryPoint;
import com.expense.gateway.security.JwtDecoderFactory;
import com.expense.gateway.security.PublicRouteAwareBearerTokenConverter;
import com.expense.gateway.security.PublicRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

	@Bean
	ReactiveJwtDecoder jwtDecoder(@Value("${gateway.jwt.jwks-uri}") String jwksUri,
			@Value("${gateway.jwt.issuer}") String issuer) {
		return JwtDecoderFactory.create(jwksUri, issuer);
	}

	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder,
			JsonAuthenticationEntryPoint entryPoint, JsonAccessDeniedHandler accessDeniedHandler) {
		return http
			// Stateless bearer-token gateway: no cookies, sessions, CSRF tokens, login forms or basic auth.
			.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
			.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
			.logout(ServerHttpSecurity.LogoutSpec::disable)
			.requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
			.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
			.authorizeExchange(exchanges -> exchanges
				.matchers(PublicRoutes.matcher()).permitAll()
				.anyExchange().authenticated())
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDeniedHandler))
			.oauth2ResourceServer(resourceServer -> resourceServer
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDeniedHandler)
				// No bearer parsing on the exact public routes, so a stale token there cannot cause a 401.
				.bearerTokenConverter(new PublicRouteAwareBearerTokenConverter())
				.jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
			.build();
	}
}
