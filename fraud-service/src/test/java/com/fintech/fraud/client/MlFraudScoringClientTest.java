package com.fintech.fraud.client;

import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.dto.FraudPredictionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("ML Fraud Scoring Client — Resilience, Protocol & Error Handling Tests")
class MlFraudScoringClientTest {

    private MockRestServiceServer mockServer;
    private MlFraudScoringClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new DefaultMlFraudScoringClient(builder.build());
    }

    private FraudPredictionRequest sampleRequest() {
        return new FraudPredictionRequest(
                BigDecimal.valueOf(250.00),
                1,
                BigDecimal.valueOf(100.00),
                2.50,
                0,
                -0.7071,
                0.7071
        );
    }

    @Test
    @DisplayName("1. Successful ML Response: Parses valid probability and model version")
    void shouldParseSuccessfulResponse() {
        String json = "{\"fraud_probability\": 0.0825, \"model_version\": \"v1.0.0\"}";

        mockServer.expect(requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        FraudPredictionResponse response = client.scoreTransaction(sampleRequest());

        assertNotNull(response);
        assertEquals(0.0825, response.fraudProbability(), 0.0001);
        assertEquals("v1.0.0", response.modelVersion());
        mockServer.verify();
    }

    @Test
    @DisplayName("2. Connection Refused: Maps to MlScoringException with CONNECTION_FAILED category")
    void shouldHandleConnectionRefused() {
        // Construct a client using a request factory that simulates connection refused
        RestClient clientWithRefusal = RestClient.builder()
                .baseUrl("http://localhost:8000")
                .requestFactory((uri, httpMethod) -> {
                    throw new ResourceAccessException("Connection refused", new ConnectException("Connection refused to 127.0.0.1:8000"));
                })
                .build();

        MlFraudScoringClient failingClient = new DefaultMlFraudScoringClient(clientWithRefusal);

        MlScoringException ex = assertThrows(
                MlScoringException.class,
                () -> failingClient.scoreTransaction(sampleRequest()),
                "Connection refused must throw MlScoringException"
        );

        assertEquals(MlScoringException.ErrorCategory.CONNECTION_FAILED, ex.getCategory());
        assertTrue(ex.getMessage().contains("connection refused") || ex.getMessage().contains("unreachable"));
    }

    @Test
    @DisplayName("3. Connection/Read Timeout: Maps to MlScoringException with TIMEOUT category")
    void shouldHandleTimeout() {
        // Construct a client using a request factory that simulates a socket timeout
        RestClient clientWithTimeout = RestClient.builder()
                .baseUrl("http://localhost:8000")
                .requestFactory((uri, httpMethod) -> {
                    throw new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out after 2000ms"));
                })
                .build();

        MlFraudScoringClient timeoutClient = new DefaultMlFraudScoringClient(clientWithTimeout);

        MlScoringException ex = assertThrows(
                MlScoringException.class,
                () -> timeoutClient.scoreTransaction(sampleRequest()),
                "Timeout must throw MlScoringException"
        );

        assertEquals(MlScoringException.ErrorCategory.TIMEOUT, ex.getCategory());
        assertTrue(ex.getMessage().toLowerCase().contains("timed out"));
    }

    @Test
    @DisplayName("4. HTTP 5xx Server Error: Maps to MlScoringException with SERVER_ERROR category")
    void shouldHandleHttp5xxError() {
        mockServer.expect(requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        MlScoringException ex = assertThrows(
                MlScoringException.class,
                () -> client.scoreTransaction(sampleRequest()),
                "HTTP 500 must throw MlScoringException"
        );

        assertEquals(MlScoringException.ErrorCategory.SERVER_ERROR, ex.getCategory());
        assertTrue(ex.getMessage().contains("500"));
        mockServer.verify();
    }

    @Test
    @DisplayName("5. Invalid Probability (> 1.0 or < 0.0): Maps to INVALID_RESPONSE")
    void shouldRejectInvalidProbability() {
        String invalidJson = "{\"fraud_probability\": 1.45, \"model_version\": \"v1.0.0\"}";

        mockServer.expect(requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(invalidJson, MediaType.APPLICATION_JSON));

        MlScoringException ex = assertThrows(
                MlScoringException.class,
                () -> client.scoreTransaction(sampleRequest()),
                "Probability > 1.0 must fail validation"
        );

        assertEquals(MlScoringException.ErrorCategory.INVALID_RESPONSE, ex.getCategory());
        assertTrue(ex.getMessage().contains("Invalid fraud_probability"));
        mockServer.verify();
    }

    @Test
    @DisplayName("5b. Negative Probability (< 0.0): Maps to INVALID_RESPONSE")
    void shouldRejectNegativeProbability() {
        String invalidJson = "{\"fraud_probability\": -0.05, \"model_version\": \"v1.0.0\"}";

        mockServer.expect(requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(invalidJson, MediaType.APPLICATION_JSON));

        MlScoringException ex = assertThrows(
                MlScoringException.class,
                () -> client.scoreTransaction(sampleRequest()),
                "Negative probability must fail validation"
        );

        assertEquals(MlScoringException.ErrorCategory.INVALID_RESPONSE, ex.getCategory());
        assertTrue(ex.getMessage().contains("Invalid fraud_probability"));
        mockServer.verify();
    }

    @Test
    @DisplayName("5c. Blank Model Version: Maps to INVALID_RESPONSE")
    void shouldRejectBlankModelVersion() {
        String invalidJson = "{\"fraud_probability\": 0.12, \"model_version\": \"   \"}";

        mockServer.expect(requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(invalidJson, MediaType.APPLICATION_JSON));

        MlScoringException ex = assertThrows(
                MlScoringException.class,
                () -> client.scoreTransaction(sampleRequest()),
                "Blank model version must fail validation"
        );

        assertEquals(MlScoringException.ErrorCategory.INVALID_RESPONSE, ex.getCategory());
        assertTrue(ex.getMessage().contains("Invalid model_version"));
        mockServer.verify();
    }

    @Test
    @DisplayName("5d. HTTP 4xx Client Error: Maps to CLIENT_ERROR category")
    void shouldHandleHttp4xxError() {
        mockServer.expect(requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        MlScoringException ex = assertThrows(
                MlScoringException.class,
                () -> client.scoreTransaction(sampleRequest()),
                "HTTP 422 must throw MlScoringException with CLIENT_ERROR"
        );

        assertEquals(MlScoringException.ErrorCategory.CLIENT_ERROR, ex.getCategory());
        assertTrue(ex.getMessage().contains("422"));
        mockServer.verify();
    }

    @Test
    @DisplayName("6. Malformed JSON / Missing Fields: Maps to INVALID_RESPONSE")
    void shouldRejectMalformedPayload() {
        String malformedJson = "{\"unexpected_key\": \"hello\"}";

        mockServer.expect(requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(malformedJson, MediaType.APPLICATION_JSON));

        MlScoringException ex = assertThrows(
                MlScoringException.class,
                () -> client.scoreTransaction(sampleRequest()),
                "Missing probability must fail validation"
        );

        assertEquals(MlScoringException.ErrorCategory.INVALID_RESPONSE, ex.getCategory());
        mockServer.verify();
    }
}
