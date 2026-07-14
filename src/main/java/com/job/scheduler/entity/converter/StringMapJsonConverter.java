package com.job.scheduler.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists a {@code Map<String, String>} (e.g. process env) as a JSON object column. */
@Converter
public class StringMapJsonConverter implements AttributeConverter<Map<String, String>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Could not serialize string map", exception);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(MAPPER.readValue(dbData, Map.class));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not read string map", exception);
        }
    }
}
