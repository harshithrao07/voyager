package com.job.scheduler.service;

import com.job.scheduler.dto.FunctionLanguageDTO;
import com.job.scheduler.dto.Judge0LimitsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Judge0ClientTest {
    private static final String BASE_URL = "http://judge0.test";

    private MockRestServiceServer server;
    private Judge0Client client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        client = new Judge0Client(restClient, new ObjectMapper());
        ReflectionTestUtils.setField(client, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(client, "authToken", "");
        ReflectionTestUtils.setField(client, "enablePerProcessAndThreadTimeLimit", true);
        ReflectionTestUtils.setField(client, "enablePerProcessAndThreadMemoryLimit", true);
    }

    // --- listLanguages ---

    @Test
    void listLanguagesParsesEntriesAndSkipsMalformed() {
        server.expect(requestTo(BASE_URL + "/languages"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"id": 71, "name": "Python (3.8.1)"},
                          {"id": "x", "name": "Bad id"},
                          {"name": "No id"},
                          {"id": 50, "name": "C (GCC 9.2.0)"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<FunctionLanguageDTO> languages = client.listLanguages();

        assertThat(languages).extracting(FunctionLanguageDTO::id).containsExactly(71, 50);
        server.verify();
    }

    @Test
    void listLanguagesReturnsEmptyWhenNotArray() {
        server.expect(requestTo(BASE_URL + "/languages"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.listLanguages()).isEmpty();
        server.verify();
    }

    @Test
    void listSelectableLanguagesFiltersPseudoLanguages() {
        server.expect(requestTo(BASE_URL + "/languages"))
                .andRespond(withSuccess("""
                        [
                          {"id": 89, "name": "Multi-file program"},
                          {"id": 71, "name": "Python (3.8.1)"},
                          {"id": 90, "name": "Executable"},
                          {"id": 43, "name": "Plain Text"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.listSelectableLanguages())
                .extracting(FunctionLanguageDTO::name)
                .containsExactly("Python (3.8.1)");
        server.verify();
    }

    // --- languageName ---

    @Test
    void languageNameResolvesKnownId() {
        server.expect(requestTo(BASE_URL + "/languages"))
                .andRespond(withSuccess("[{\"id\": 71, \"name\": \"Python (3.8.1)\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.languageName(71)).isEqualTo("Python (3.8.1)");
        server.verify();
    }

    @Test
    void languageNameReturnsNullForUnknownId() {
        server.expect(requestTo(BASE_URL + "/languages"))
                .andRespond(withSuccess("[{\"id\": 71, \"name\": \"Python (3.8.1)\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.languageName(999)).isNull();
        server.verify();
    }

    // --- createSubmission ---

    @Test
    void createSubmissionPostsBodyAndReturnsToken() {
        server.expect(requestTo(BASE_URL + "/submissions/?base64_encoded=false&wait=false"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.language_id").value(71))
                .andExpect(jsonPath("$.source_code").value("print(1)"))
                .andExpect(jsonPath("$.enable_per_process_and_thread_time_limit").value(true))
                .andRespond(withSuccess("{\"token\": \"tok-123\"}", MediaType.APPLICATION_JSON));

        String token = client.createSubmission(submissionRequest());

        assertThat(token).isEqualTo("tok-123");
        server.verify();
    }

    @Test
    void createSubmissionThrowsWhenTokenMissing() {
        server.expect(requestTo(BASE_URL + "/submissions/?base64_encoded=false&wait=false"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createSubmission(submissionRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not return a submission token");
        server.verify();
    }

    // --- getSubmission ---

    @Test
    void getSubmissionMapsNestedStatusAndCoercesStringNumbers() {
        server.expect(requestTo(BASE_URL + "/submissions/tok-123?base64_encoded=false"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "token": "tok-123",
                          "status": {"id": 3, "description": "Accepted"},
                          "stdout": "hi",
                          "stderr": null,
                          "exit_code": "0",
                          "time": "0.12",
                          "wall_time": 0.20,
                          "memory": "2048"
                        }
                        """, MediaType.APPLICATION_JSON));

        Judge0SubmissionResult result = client.getSubmission("tok-123");

        assertThat(result.token()).isEqualTo("tok-123");
        assertThat(result.statusId()).isEqualTo(3);
        assertThat(result.statusDescription()).isEqualTo("Accepted");
        assertThat(result.stdout()).isEqualTo("hi");
        assertThat(result.stderr()).isNull();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.timeSeconds()).isEqualTo(0.12);
        assertThat(result.wallTimeSeconds()).isEqualTo(0.20);
        assertThat(result.memoryKb()).isEqualTo(2048L);
        server.verify();
    }

    // --- countStatuses ---

    @Test
    void countStatusesReturnsArraySize() {
        server.expect(requestTo(BASE_URL + "/statuses"))
                .andRespond(withSuccess("[{},{},{}]", MediaType.APPLICATION_JSON));

        assertThat(client.countStatuses()).isEqualTo(3);
        server.verify();
    }

    @Test
    void countStatusesReturnsZeroWhenNotArray() {
        server.expect(requestTo(BASE_URL + "/statuses"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.countStatuses()).isZero();
        server.verify();
    }

    // --- configInfo ---

    @Test
    void configInfoMapsLimitsAcrossTypes() {
        server.expect(requestTo(BASE_URL + "/config_info"))
                .andRespond(withSuccess("""
                        {
                          "cpu_time_limit": "2.0",
                          "max_cpu_time_limit": 15.0,
                          "wall_time_limit": 5.0,
                          "max_wall_time_limit": 20.0,
                          "memory_limit": 128000,
                          "max_memory_limit": "512000",
                          "max_file_size": 1024,
                          "max_extract_size": 4096,
                          "enable_network": 1,
                          "allow_enable_network": "true"
                        }
                        """, MediaType.APPLICATION_JSON));

        Judge0LimitsDTO limits = client.configInfo();

        assertThat(limits.cpuTimeLimit()).isEqualTo(2.0);
        assertThat(limits.maxCpuTimeLimit()).isEqualTo(15.0);
        assertThat(limits.memoryLimit()).isEqualTo(128000L);
        assertThat(limits.maxMemoryLimit()).isEqualTo(512000L);
        assertThat(limits.maxFileSize()).isEqualTo(1024);
        assertThat(limits.enableNetwork()).isTrue();       // int 1 -> true
        assertThat(limits.allowEnableNetwork()).isTrue();  // string "true" -> true
        server.verify();
    }

    // --- workerStats ---

    @Test
    void workerStatsSumsAvailableAndIdleAcrossPools() {
        server.expect(requestTo(BASE_URL + "/workers"))
                .andRespond(withSuccess("""
                        [
                          {"available": 2, "idle": 1},
                          {"available": 3, "idle": 2}
                        ]
                        """, MediaType.APPLICATION_JSON));

        Judge0Client.WorkerStats stats = client.workerStats();

        assertThat(stats.total()).isEqualTo(5);
        assertThat(stats.available()).isEqualTo(3);
        server.verify();
    }

    @Test
    void workerStatsReturnsZeroesWhenNotArray() {
        server.expect(requestTo(BASE_URL + "/workers"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Judge0Client.WorkerStats stats = client.workerStats();

        assertThat(stats.total()).isZero();
        assertThat(stats.available()).isZero();
        server.verify();
    }

    // --- JSON coercion helper edge cases ---

    @Test
    void blankStringEncodedNumbersCoerceToNull() {
        server.expect(requestTo(BASE_URL + "/submissions/tok-1?base64_encoded=false"))
                .andRespond(withSuccess("""
                        {
                          "token": "tok-1",
                          "status": {"id": "", "description": "x"},
                          "exit_code": "",
                          "time": "",
                          "memory": ""
                        }
                        """, MediaType.APPLICATION_JSON));

        Judge0SubmissionResult result = client.getSubmission("tok-1");

        assertThat(result.statusId()).isNull();
        assertThat(result.exitCode()).isNull();
        assertThat(result.timeSeconds()).isNull();
        assertThat(result.memoryKb()).isNull();
        server.verify();
    }

    @Test
    void boolValueHandlesIntZeroAndUnsupportedTypes() {
        server.expect(requestTo(BASE_URL + "/config_info"))
                .andRespond(withSuccess("""
                        {
                          "enable_network": 0,
                          "allow_enable_network": {"nested": true}
                        }
                        """, MediaType.APPLICATION_JSON));

        Judge0LimitsDTO limits = client.configInfo();

        assertThat(limits.enableNetwork()).isFalse();        // int 0 -> false
        assertThat(limits.allowEnableNetwork()).isNull();    // object -> unhandled -> null
        assertThat(limits.cpuTimeLimit()).isNull();          // absent field -> null
        server.verify();
    }

    @Test
    void emptyResponseBodyIsTreatedAsEmptyObject() {
        // An empty 200 body makes RestClient return null, exercising readTree's null guard.
        server.expect(requestTo(BASE_URL + "/statuses"))
                .andRespond(withSuccess());

        assertThat(client.countStatuses()).isZero();
        server.verify();
    }

    // --- auth header ---

    @Test
    void authTokenIsSentAsHeaderWhenConfigured() {
        ReflectionTestUtils.setField(client, "authToken", "secret-token");
        server.expect(requestTo(BASE_URL + "/statuses"))
                .andExpect(header("X-Auth-Token", "secret-token"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.countStatuses();
        server.verify();
    }

    // --- error and URL normalization ---

    @Test
    void invalidJsonResponseThrows() {
        server.expect(requestTo(BASE_URL + "/statuses"))
                .andRespond(withSuccess("not json", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.countStatuses())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Judge0 returned invalid JSON");
        server.verify();
    }

    @Test
    void baseUrlTrailingSlashesAreTrimmed() {
        ReflectionTestUtils.setField(client, "baseUrl", "http://judge0.test///");
        server.expect(requestTo(BASE_URL + "/statuses"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.countStatuses()).isZero();
        server.verify();
    }

    @Test
    void blankBaseUrlFallsBackToLocalhost() {
        ReflectionTestUtils.setField(client, "baseUrl", "");
        server.expect(requestTo("http://localhost:2358/statuses"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.countStatuses()).isZero();
        server.verify();
    }

    private Judge0SubmissionRequest submissionRequest() {
        return new Judge0SubmissionRequest(
                71,
                "print(1)",
                null,
                "{}",
                null,
                null,
                2.0,
                5.0,
                131072,
                1024,
                false
        );
    }
}
