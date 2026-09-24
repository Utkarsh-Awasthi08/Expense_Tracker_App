package org.example.Otp;

import org.example.Api.CooldownActiveException;
import org.example.Api.InvalidOtpException;
import org.example.Api.InvalidPhoneNumberException;
import org.example.Api.RateLimitedException;
import org.example.Entities.OtpChallenge;
import org.example.Entities.UserInfo;
import org.example.Entities.UserRole;
import org.example.Repository.OtpChallengeRepository;
import org.example.Repository.RoleRepository;
import org.example.Repository.UserRepository;
import org.example.Response.JwtResponseDTO;
import org.example.Service.JwtService;
import org.example.Service.RefreshTokenService;
import org.example.Sms.SmsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtpServiceTest {

    private final OtpChallengeRepository otpChallengeRepository = mock(OtpChallengeRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final SmsProvider smsProvider = mock(SmsProvider.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);

    private final Instant fixedNow = Instant.parse("2026-09-24T10:00:00Z");
    private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
    private final OtpProperties properties = new OtpProperties(300, 60, 5, 5, "91");

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(otpChallengeRepository, userRepository, roleRepository,
                smsProvider, jwtService, refreshTokenService, properties, clock);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- requestOtp tests --------------------------------------------------------------------------------------

    @Test
    void requestOtpSuccessSavesChallengeAndSendsSms() {
        when(otpChallengeRepository.countByPhoneNumberAndCreatedAtAfter(eq("+919876543210"), any())).thenReturn(0L);
        when(otpChallengeRepository.findFirstByPhoneNumberOrderByCreatedAtDesc("+919876543210")).thenReturn(Optional.empty());

        otpService.requestOtp("98765-43210");

        ArgumentCaptor<OtpChallenge> captor = ArgumentCaptor.forClass(OtpChallenge.class);
        verify(otpChallengeRepository).save(captor.capture());
        OtpChallenge saved = captor.getValue();
        assertThat(saved.getPhoneNumber()).isEqualTo("+919876543210");
        assertThat(saved.getAttemptsRemaining()).isEqualTo(5);
        assertThat(saved.getExpiresAt()).isEqualTo(fixedNow.plusSeconds(300));

        verify(otpChallengeRepository).consumeActiveForPhone("+919876543210", fixedNow);
        verify(smsProvider).sendOtp(eq("+919876543210"), anyString());
    }

    @Test
    void requestOtpWithInvalidPhoneNumberThrows() {
        assertThatThrownBy(() -> otpService.requestOtp("123"))
                .isInstanceOf(InvalidPhoneNumberException.class);
        verify(otpChallengeRepository, never()).save(any());
    }

    @Test
    void requestOtpWithinCooldownThrows() {
        when(otpChallengeRepository.countByPhoneNumberAndCreatedAtAfter(eq("+919876543210"), any())).thenReturn(1L);
        OtpChallenge recent = new OtpChallenge("+919876543210", "hash", fixedNow.plusSeconds(300), 5, fixedNow.minusSeconds(20));
        when(otpChallengeRepository.findFirstByPhoneNumberOrderByCreatedAtDesc("+919876543210")).thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> otpService.requestOtp("+919876543210"))
                .isInstanceOf(CooldownActiveException.class)
                .satisfies(e -> assertThat(((CooldownActiveException) e).retryAfterSeconds()).isEqualTo(40));
    }

    @Test
    void requestOtpExceedingRateLimitThrows() {
        when(otpChallengeRepository.countByPhoneNumberAndCreatedAtAfter(eq("+919876543210"), any())).thenReturn(5L);
        when(otpChallengeRepository.findOldestCreatedAtSince(eq("+919876543210"), any()))
                .thenReturn(Optional.of(fixedNow.minusSeconds(1800)));

        assertThatThrownBy(() -> otpService.requestOtp("+919876543210"))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(e -> assertThat(((RateLimitedException) e).retryAfterSeconds()).isEqualTo(1800));
    }

    // ---- verifyOtp tests ---------------------------------------------------------------------------------------

    @Test
    void verifyOtpSuccessAutoProvisionsUserAndIssuesTokens() {
        String phone = "+919876543210";
        String code = "123456";
        String hash = sha256Hex(code + ":" + phone);

        OtpChallenge challenge = new OtpChallenge(phone, hash, fixedNow.plusSeconds(300), 5, fixedNow);
        challenge.setId(101L);

        when(otpChallengeRepository.findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(phone))
                .thenReturn(Optional.of(challenge));
        when(otpChallengeRepository.decrementAttempts(101L)).thenReturn(1);
        when(otpChallengeRepository.consumeById(101L, fixedNow)).thenReturn(1);
        when(userRepository.findByPhoneNumber(phone)).thenReturn(Optional.empty());
        when(roleRepository.findByRoleName("ROLE_USER")).thenReturn(Optional.of(new UserRole("ROLE_USER")));

        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            UserInfo u = invocation.getArgument(0);
            return u;
        });

        when(jwtService.issueAccessToken(anyString(), eq(phone), eq(List.of("ROLE_USER")))).thenReturn("access-token-123");
        when(refreshTokenService.issue(anyString())).thenReturn("refresh-token-456");
        when(jwtService.accessTtlSeconds()).thenReturn(900L);

        JwtResponseDTO response = otpService.verifyOtp("9876543210", code);

        assertThat(response.accessToken()).isEqualTo("access-token-123");
        assertThat(response.token()).isEqualTo("refresh-token-456");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900L);

        verify(otpChallengeRepository).consumeById(101L, fixedNow);
        verify(userRepository).saveAndFlush(any(UserInfo.class));
    }

    @Test
    void verifyOtpWrongCodeThrowsInvalidOtpException() {
        String phone = "+919876543210";
        String hash = sha256Hex("123456:" + phone);
        OtpChallenge challenge = new OtpChallenge(phone, hash, fixedNow.plusSeconds(300), 5, fixedNow);
        challenge.setId(101L);

        when(otpChallengeRepository.findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(phone))
                .thenReturn(Optional.of(challenge));
        when(otpChallengeRepository.decrementAttempts(101L)).thenReturn(1);

        assertThatThrownBy(() -> otpService.verifyOtp(phone, "999999"))
                .isInstanceOf(InvalidOtpException.class);

        verify(otpChallengeRepository, never()).consumeById(anyLong(), any());
    }

    @Test
    void verifyOtpExpiredChallengeThrows() {
        String phone = "+919876543210";
        OtpChallenge expired = new OtpChallenge(phone, "hash", fixedNow.minusSeconds(10), 5, fixedNow.minusSeconds(310));
        expired.setId(101L);

        when(otpChallengeRepository.findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(phone))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> otpService.verifyOtp(phone, "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void verifyOtpExhaustedAttemptsThrows() {
        String phone = "+919876543210";
        OtpChallenge challenge = new OtpChallenge(phone, "hash", fixedNow.plusSeconds(300), 1, fixedNow);
        challenge.setId(101L);

        when(otpChallengeRepository.findFirstByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(phone))
                .thenReturn(Optional.of(challenge));
        when(otpChallengeRepository.decrementAttempts(101L)).thenReturn(0); // already exhausted

        assertThatThrownBy(() -> otpService.verifyOtp(phone, "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }
}
