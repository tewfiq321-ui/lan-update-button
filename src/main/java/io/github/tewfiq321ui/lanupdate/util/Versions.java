package io.github.tewfiq321ui.lanupdate.util;

import java.util.Arrays;

/**
 * Compares version strings like "1.2.10", "v1.3.0" and "1.3.0-beta.2".
 * Numbers compare numerically, a missing number counts as 0, and a qualifier such as
 * "beta" makes a version older than the same version without it.
 */
public final class Versions {
    private Versions() {}

    public static int compare(String a, String b) {
        String[] left = tokens(a);
        String[] right = tokens(b);
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int result = compareToken(i < left.length ? left[i] : null, i < right.length ? right[i] : null);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static String[] tokens(String version) {
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        return Arrays.stream(trimmed.split("[.\\-+_]")).filter(token -> !token.isEmpty()).toArray(String[]::new);
    }

    private static int compareToken(String x, String y) {
        if (x == null && y == null) {
            return 0;
        }
        if (x == null) {
            return isNumber(y) ? compareNumbers("0", y) : 1;
        }
        if (y == null) {
            return isNumber(x) ? compareNumbers(x, "0") : -1;
        }
        boolean xNumber = isNumber(x);
        boolean yNumber = isNumber(y);
        if (xNumber && yNumber) {
            return compareNumbers(x, y);
        }
        if (xNumber) {
            return 1;
        }
        if (yNumber) {
            return -1;
        }
        return x.compareToIgnoreCase(y);
    }

    private static boolean isNumber(String token) {
        return !token.isEmpty() && token.chars().allMatch(Character::isDigit);
    }

    private static int compareNumbers(String x, String y) {
        String a = stripLeadingZeros(x);
        String b = stripLeadingZeros(y);
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return a.compareTo(b);
    }

    private static String stripLeadingZeros(String number) {
        int i = 0;
        while (i < number.length() - 1 && number.charAt(i) == '0') {
            i++;
        }
        return number.substring(i);
    }
}
