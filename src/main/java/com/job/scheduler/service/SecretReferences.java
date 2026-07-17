package com.job.scheduler.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Syntax and validation for references that are safe to persist. */
public final class SecretReferences {
    private static final Pattern REFERENCE = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
    private static final Pattern TEMPLATE = Pattern.compile(
            "^(?:\\$\\{secret:([A-Z][A-Z0-9_]{0,127})}|ref:([A-Z][A-Z0-9_]{0,127}))$"
    );
    private static final Pattern SENSITIVE_ENV_NAME = Pattern.compile(
            "(^|_)(TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|PRIVATE_KEY|CREDENTIAL|ACCESS_KEY|AUTH)(_|$)"
    );

    private SecretReferences() {
    }

    public static String requireValidReference(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Secret reference cannot be blank");
        }
        String normalized = value.trim();
        if (!REFERENCE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Secret reference must use UPPER_SNAKE_CASE"
            );
        }
        return normalized;
    }

    public static Optional<String> referenceFromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim();
        Matcher matcher = TEMPLATE.matcher(normalized);
        if (matcher.matches()) {
            return Optional.of(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        }
        if (normalized.startsWith("${secret:") || normalized.startsWith("ref:")) {
            throw new IllegalArgumentException(
                    "Secret environment references must use ${secret:UPPER_SNAKE_CASE} "
                            + "or ref:UPPER_SNAKE_CASE"
            );
        }
        return Optional.empty();
    }

    public static void validateEnvironment(Map<String, String> environment) {
        if (environment == null) {
            return;
        }
        environment.forEach((name, value) -> {
            Optional<String> reference = referenceFromValue(value);
            if (reference.isPresent()) {
                requireValidReference(reference.get());
                return;
            }
            String normalizedName = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
            if (SENSITIVE_ENV_NAME.matcher(normalizedName).find()) {
                throw new IllegalArgumentException(
                        "Environment variable " + normalizedName
                                + " looks sensitive and must use ${secret:REF} or ref:REF"
                );
            }
        });
    }
}
