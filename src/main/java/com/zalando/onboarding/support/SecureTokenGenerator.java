package com.zalando.onboarding.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Mints the two unguessable strings the system hands out, and hashes the one it stores.
 *
 * <p>Both are bearer credentials (T-3): with no authentication anywhere in this system,
 * whoever holds the string can read the application, so the security property rests
 * entirely on entropy from {@link SecureRandom}. Neither value is derived from a database
 * sequence, a timestamp or anything else enumerable.
 */
@Component
public class SecureTokenGenerator {

    /** 256 bits, comfortably above the 128-bit floor. */
    private static final int DRAFT_TOKEN_BYTES = 32;

    /**
     * Crockford base32: no I, L, O or U, so a reference read down the phone to support
     * cannot be transcribed into a different valid-looking one.
     */
    private static final char[] REFERENCE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final String REFERENCE_PREFIX = "ONB-";

    /** 16 symbols over a 32-symbol alphabet: 80 bits, and 20 chars fits VARCHAR(24). */
    private static final int REFERENCE_SYMBOLS = 16;

    private final SecureRandom random = new SecureRandom();

    /** URL-safe so it can be dropped straight into a resume link. Returned to the caller once. */
    public String newDraftToken() {
        byte[] bytes = new byte[DRAFT_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * What we persist in place of the token.
     *
     * <p>Plain SHA-256, unsalted and deliberately so: the token must be findable by hash on
     * resume, which rules out a per-row salt. A password KDF would be the wrong tool here —
     * the input is 256 random bits, not a guessable secret, so there is no dictionary to
     * slow an attacker down against.
     */
    public String hashDraftToken(String draftToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(draftToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** Non-sequential by construction, e.g. {@code ONB-4T9BQZ2M7XKPR13V}. */
    public String newReference() {
        StringBuilder reference = new StringBuilder(REFERENCE_PREFIX.length() + REFERENCE_SYMBOLS);
        reference.append(REFERENCE_PREFIX);
        for (int i = 0; i < REFERENCE_SYMBOLS; i++) {
            // nextInt(bound) rejection-samples internally, so the alphabet stays uniform.
            reference.append(REFERENCE_ALPHABET[random.nextInt(REFERENCE_ALPHABET.length)]);
        }
        return reference.toString();
    }
}
