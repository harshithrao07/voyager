package com.job.scheduler.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Persists a {@code List<String>} (e.g. process args) as a JSON array column. */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Could not serialize string list", exception);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Arrays.asList(MAPPER.readValue(dbData, String[].class)));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not read string list", exception);
        }
    }
}
