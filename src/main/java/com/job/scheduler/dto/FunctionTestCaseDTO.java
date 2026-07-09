package com.job.scheduler.dto;

import jakarta.validation.constraints.Size;

/**
 * A saved test case for a function version: JSON input piped to stdin plus the
 * output/error the author expected when they last ran it in the workbench.
 */
public record FunctionTestCaseDTO(
        @Size(max = 200, message = "test case name must be at most 200 characters")
        String name,

        @Size(max = 65_536, message = "test case input must be at most 65536 characters")
        String input,

        @Size(max = 65_536, message = "test case expectedOutput must be at most 65536 characters")
        String expectedOutput,

        @Size(max = 65_536, message = "test case expectedError must be at most 65536 characters")
        String expectedError
) {
}
