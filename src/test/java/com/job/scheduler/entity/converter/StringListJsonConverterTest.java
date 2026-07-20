package com.job.scheduler.entity.converter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StringListJsonConverterTest {
    private final StringListJsonConverter converter = new StringListJsonConverter();

    @Test
    void serializesPopulatedListAsJsonArray() {
        assertThat(converter.convertToDatabaseColumn(List.of("--fast", "-O2")))
                .isEqualTo("[\"--fast\",\"-O2\"]");
    }

    @Test
    void serializesNullListAsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void serializesEmptyListAsNull() {
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
    }

    @Test
    void deserializesJsonArrayToList() {
        assertThat(converter.convertToEntityAttribute("[\"a\",\"b\"]"))
                .containsExactly("a", "b");
    }

    @Test
    void deserializesNullToEmptyMutableList() {
        List<String> result = converter.convertToEntityAttribute(null);

        assertThat(result).isEmpty();
        result.add("mutable");
        assertThat(result).containsExactly("mutable");
    }

    @Test
    void deserializesBlankToEmptyList() {
        assertThat(converter.convertToEntityAttribute("   ")).isEmpty();
    }

    @Test
    void roundTripPreservesOrder() {
        List<String> original = List.of("one", "two", "three");

        String column = converter.convertToDatabaseColumn(original);

        assertThat(converter.convertToEntityAttribute(column))
                .containsExactlyElementsOf(original);
    }

    @Test
    void deserializingMalformedJsonThrowsIllegalState() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not read string list");
    }
}
