package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionLanguageDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps Voyager function runtimes limited to languages with a clear
 * stdin-JSON/stdout-JSON workflow contract. Operators can still pin exact
 * Judge0 ids when their runtime image differs from the default Judge0 catalog.
 */
@Service
public class FunctionRuntimePolicy {
    private static final String DEFAULT_RUNTIME_DESCRIPTION =
            "all real Judge0 program runtimes except SQL and pseudo-runtimes";
    private static final Set<String> PSEUDO_RUNTIME_NAMES = Set.of(
            "executable",
            "multi-file program",
            "plain text"
    );

    private final Judge0Client judge0Client;

    @Value("${scheduler.judge0.allowed-language-ids:}")
    private String allowedLanguageIdsProperty;

    public FunctionRuntimePolicy(Judge0Client judge0Client) {
        this.judge0Client = judge0Client;
    }

    public List<FunctionLanguageDTO> supportedSelectableLanguages() {
        return supportedLanguages(judge0Client.listSelectableLanguages());
    }

    public List<FunctionLanguageDTO> supportedLanguages(
            List<FunctionLanguageDTO> languages
    ) {
        Set<Integer> allowedIds = configuredAllowedLanguageIds();
        return languages.stream()
                .filter(language -> isSupported(language, allowedIds))
                .toList();
    }

    public void assertLanguageSupported(Integer languageId) {
        if (languageId == null) {
            throw new IllegalArgumentException("Function language is required");
        }
        Set<Integer> allowedIds = configuredAllowedLanguageIds();
        if (!allowedIds.isEmpty()) {
            if (!allowedIds.contains(languageId)) {
                throw new IllegalArgumentException(
                        "Language " + languageId
                                + " is not supported for Voyager functions. "
                                + "Allowed language ids: " + allowedIds
                );
            }
            return;
        }

        String languageName = judge0Client.languageName(languageId);
        if (!isDefaultSupported(languageName)) {
            throw new IllegalArgumentException(
                    "Language " + languageId
                            + " is not supported for Voyager functions. "
                            + "Supported runtimes: "
                            + DEFAULT_RUNTIME_DESCRIPTION
            );
        }
    }

    private boolean isSupported(
            FunctionLanguageDTO language,
            Set<Integer> allowedIds
    ) {
        if (!allowedIds.isEmpty()) {
            return allowedIds.contains(language.id());
        }
        return isDefaultSupported(language.name());
    }

    private boolean isDefaultSupported(String languageName) {
        if (languageName == null || languageName.isBlank()) {
            return false;
        }
        String name = languageName.toLowerCase(Locale.ROOT);
        if (PSEUDO_RUNTIME_NAMES.contains(name.trim())) {
            return false;
        }
        return !name.contains("sql") && !name.contains("sqlite");
    }

    private Set<Integer> configuredAllowedLanguageIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        if (allowedLanguageIdsProperty == null
                || allowedLanguageIdsProperty.isBlank()) {
            return ids;
        }
        for (String token : allowedLanguageIdsProperty.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // Skip malformed entries so one typo does not disable functions.
            }
        }
        return ids;
    }
}
