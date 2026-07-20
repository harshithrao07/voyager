package com.job.scheduler.entity.converter;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StringMapJsonConverterTest {
    private final StringMapJsonConverter converter = new StringMapJsonConverter();

    @Test
    void serializesPopulatedMapAsJsonObject() {
        assertThat(converter.convertToDatabaseColumn(Map.of("LOG_LEVEL", "info")))
                .isEqualTo("{\"LOG_LEVEL\":\"info\"}");
    }

    @Test
    void serializesNullMapAsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void serializesEmptyMapAsNull() {
        assertThat(converter.convertToDatabaseColumn(Map.of())).isNull();
    }

    @Test
    void deserializesJsonObjectToMap() {
        assertThat(converter.convertToEntityAttribute("{\"k\":\"v\"}"))
                .containsEntry("k", "v");
    }

    @Test
    void deserializesNullToEmptyMutableMap() {
        Map<String, String> result = converter.convertToEntityAttribute(null);

        assertThat(result).isEmpty();
        result.put("mutable", "yes");
        assertThat(result).containsEntry("mutable", "yes");
    }

    @Test
    void deserializesBlankToEmptyMap() {
        assertThat(converter.convertToEntityAttribute("  ")).isEmpty();
    }

    @Test
    void roundTripPreservesInsertionOrder() {
        Map<String, String> original = new LinkedHashMap<>();
        original.put("first", "1");
        original.put("second", "2");
        original.put("third", "3");

        String column = converter.convertToDatabaseColumn(original);

        assertThat(converter.convertToEntityAttribute(column))
                .containsExactlyEntriesOf(original);
    }

    @Test
    void deserializingMalformedJsonThrowsIllegalState() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not read string map");
    }
}
