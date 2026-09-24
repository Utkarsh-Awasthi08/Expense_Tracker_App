package com.expenseTracker.userService.Validation;

/**
 * Unicode-aware text checks. {@link String#isBlank()} and {@link String#strip()} only know
 * {@link Character#isWhitespace(int)}, which misses the no-break spaces (NBSP, narrow NBSP, figure space) and every
 * zero-width character, so a "name" made only of those would look non-blank to a regex like {@code \S}.
 */
public final class UnicodeText {

    private UnicodeText() {
    }

    /**
     * A character that renders as nothing (or as empty space): Java whitespace, any Unicode space separator
     * (Zs/Zl/Zp: NBSP, em space, ideographic space, U+2028...), any format character (Cf: zero-width space, joiners,
     * word joiner, BOM, soft hyphen, bidi marks) and the blank-looking Hangul fillers and braille blank.
     */
    public static boolean isBlankChar(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.FORMAT
                || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800;
    }

    /** True for null, empty, or a value made only of {@link #isBlankChar blank characters}. */
    public static boolean isBlank(String value) {
        return value == null || value.codePoints().allMatch(UnicodeText::isBlankChar);
    }

    /**
     * ISO control characters (NUL, tab, line feed, DEL, C1 controls...) plus the Unicode line and paragraph
     * separators, none of which belong in a name, an e-mail address or a picture URL.
     */
    public static boolean isControlChar(int cp) {
        if (Character.isISOControl(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    public static boolean hasControlCharacters(String value) {
        return value != null && value.codePoints().anyMatch(UnicodeText::isControlChar);
    }

    /** Removes {@link #isBlankChar blank characters} from both ends only; inner characters are kept as they are. */
    public static String strip(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!isBlankChar(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!isBlankChar(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }
}
