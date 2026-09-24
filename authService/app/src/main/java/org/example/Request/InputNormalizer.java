package org.example.Request;

import java.util.regex.Pattern;

/**
 * Phone-number normalisation shared by OTP request and verify, so a number is stored, hashed, looked up and logged
 * in exactly one canonical form.
 *
 * <p>Separators people type (spaces, hyphens, dots, parentheses) are stripped first; the result must then match
 * {@code ^\+?[0-9]{7,15}$}. A number with no leading '+' is assumed to be in the configured default country and is
 * prefixed with it, producing {@code +<countrycode><digits>}. Anything that does not normalise cleanly returns
 * {@code null}; the caller turns that into a 400 VALIDATION_FAILED.
 */
public final class InputNormalizer {

    private static final Pattern E164_ISH = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final String FALLBACK_COUNTRY_CODE = "91";

    private InputNormalizer() {
    }

    public static String otpPhoneNumber(String raw, String defaultCountryCode) {
        if (raw == null) {
            return null;
        }
        String stripped = stripSeparators(raw);
        if (!E164_ISH.matcher(stripped).matches()) {
            return null;
        }
        if (stripped.startsWith("+")) {
            return stripped;
        }
        String countryCode = defaultCountryCode == null || defaultCountryCode.isBlank()
                ? FALLBACK_COUNTRY_CODE : defaultCountryCode.trim();
        return "+" + countryCode + stripped;
    }

    private static String stripSeparators(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != ' ' && c != '-' && c != '.' && c != '(' && c != ')') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
