package org.example.Controller;

import com.nimbusds.jose.jwk.JWKSet;
import lombok.RequiredArgsConstructor;
import org.example.Auth.JwtKeyProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Publishes the public signing key so that the gateway can verify tokens. Only public members are exposed. */
@RestController
@RequiredArgsConstructor
public class JwksController
{
    private final JwtKeyProvider keys;

    @GetMapping("/auth/v1/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(new JWKSet(keys.publicJwk()).toJSONObject(true));
    }
}
