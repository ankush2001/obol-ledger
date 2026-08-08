package dev.ankush.obol.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Formatting only. The ledger stores and reasons in integer minor units --
 * cents, centavos, paise -- and never in decimals, because binary floating
 * point cannot represent a tenth and repeated rounding of a decimal type is
 * how a ledger acquires a drift nobody can explain.
 *
 * <p>These helpers exist so a response can carry a human-readable "12.34"
 * alongside the authoritative 1234, not so that arithmetic can be done on it.
 */
public final class Money {

    private Money() {
    }

    /** Minor units to a decimal string, using the currency's own scale. */
    public static String format(long minor, String currencyCode) {
        int scale = fractionDigits(currencyCode);
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }

    /**
     * Decimal string to minor units, refusing anything with more precision
     * than the currency has. "1.005" in USD is not a rounding question, it is
     * a caller who does not know what they are asking for.
     */
    public static long parse(String amount, String currencyCode) {
        int scale = fractionDigits(currencyCode);
        BigDecimal value = new BigDecimal(amount);
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "%s cannot be expressed in %s, which has %d decimal places"
                            .formatted(amount, currencyCode, scale));
        }
    }

    private static int fractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            // -1 means a pseudo-currency with no minor unit (XAU, XDR).
            return Math.max(digits, 0);
        } catch (IllegalArgumentException e) {
            // Unknown code: two places is the overwhelmingly common case, and
            // the stored integer is unaffected either way.
            return 2;
        }
    }
}
