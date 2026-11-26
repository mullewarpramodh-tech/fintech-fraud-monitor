package com.fintech.fraud.client;

import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.dto.FraudPredictionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Java-to-FastAPI HTTP Integration Test.
 *
 * Exercises REAL TCP HTTP network transmission between the Spring RestClient
 * and the running Uvicorn ASGI server hosting FastAPI.
 * No HTTP mocks or WireMock stubs are used.
 *
 * Automatically activated via Maven profile 'live-http' or property -Dlive.ml.test=true.
 */
@EnabledIfSystemProperty(
        named = "live.ml.test",
        matches = "true",
        disabledReason = "Live ML integration test requires running FastAPI server (activate with -Plive-http or -Dlive.ml.test=true)"
)
@DisplayName("Live Java-to-FastAPI HTTP Integration Tests (No Mocks)")
class MlFraudScoringLiveIntegrationTest {

    private String liveServerUrl;
    private MlFraudScoringClient liveClient;

    @BeforeEach
    void setUp() {
        liveServerUrl = System.getProperty("ml.service.url",
                System.getenv().getOrDefault("ML_SERVICE_URL", "http://127.0.0.1:8000"));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(3000));
        requestFactory.setReadTimeout(Duration.ofMillis(3000));

        RestClient restClient = RestClient.builder()
                .baseUrl(liveServerUrl)
                .requestFactory(requestFactory)
                .build();

        liveClient = new DefaultMlFraudScoringClient(restClient);
    }

    @Test
    @DisplayName("1. Live GET /health: Verifies running FastAPI server has loaded ML model pipeline")
    void shouldVerifyLiveFastApiHealthAndModelReadiness() {
        RestClient healthClient = RestClient.builder().baseUrl(liveServerUrl).build();

        String healthJson = healthClient.get()
                .uri("/health")
                .retrieve()
                .body(String.class);

        assertNotNull(healthJson, "Health check response must not be null");
        assertTrue(healthJson.contains("\"status\":\"UP\"") || healthJson.contains("\"status\": \"UP\""),
                "FastAPI server must report UP status: " + healthJson);
        assertTrue(healthJson.contains("\"model_loaded\":true") || healthJson.contains("\"model_loaded\": true"),
                "FastAPI server must have model loaded in memory: " + healthJson);
        assertTrue(healthJson.contains("\"model_version\""),
                "Health response must contain model_version attribute");
    }

    @Test
    @DisplayName("2. Live POST /predict (Clean Transaction): Verifies real HTTP request with all 7 features returns valid probability")
    void shouldScoreCleanTransactionOverRealHttpConnection() {
        // Construct real feature payload with all 7 engineered features
        FraudPredictionRequest cleanRequest = new FraudPredictionRequest(
                BigDecimal.valueOf(85.00),     // amount
                0,                             // tx_count_last_1h
                BigDecimal.valueOf(100.00),    // avg_amount_last_24h
                0.85,                          // amount_to_avg_ratio
                0,                             // velocity_60s
                0.0,                           // sin_hour
                1.0                            // cos_hour
        );

        // Execute real HTTP POST /predict over TCP to running FastAPI
        FraudPredictionResponse response = liveClient.scoreTransaction(cleanRequest);

        assertNotNull(response, "ML response over live HTTP must not be null");
        assertNotNull(response.fraudProbability(), "Fraud probability must be non-null");
        assertTrue(response.fraudProbability() >= 0.0 && response.fraudProbability() <= 1.0,
                "Fraud probability must be calibrated between 0.0 and 1.0, got: " + response.fraudProbability());

        assertNotNull(response.modelVersion(), "Model version must be non-null");
        assertFalse(response.modelVersion().isBlank(), "Model version must not be blank");
        assertEquals("v1.0.0", response.modelVersion(), "Model version must match trained artifact");

        // Clean transaction typically scores low probability
        assertTrue(response.fraudProbability() < 0.14,
                "Clean transaction should score below fraud threshold (0.14), got: " + response.fraudProbability());
    }

    @Test
    @DisplayName("3. Live POST /predict (High-Risk Burst Transaction): Verifies real inference detects elevated fraud probability")
    void shouldScoreHighRiskTransactionOverRealHttpConnection() {
        FraudPredictionRequest burstRequest = new FraudPredictionRequest(
                BigDecimal.valueOf(4500.00),   // amount: large deviation
                5,                             // tx_count_last_1h: high frequency
                BigDecimal.valueOf(65.00),     // avg_amount_last_24h: low baseline
                69.23,                         // amount_to_avg_ratio: huge surge
                3,                             // velocity_60s: rapid burst
                0.7071,                        // sin_hour: unusual time
                -0.7071                        // cos_hour
        );

        FraudPredictionResponse response = liveClient.scoreTransaction(burstRequest);

        assertNotNull(response);
        assertTrue(response.fraudProbability() >= 0.0 && response.fraudProbability() <= 1.0);
        assertEquals("v1.0.0", response.modelVersion());

        // Burst / surge transaction scores higher probability than clean baseline
        assertTrue(response.fraudProbability() > 0.05,
                "Burst transaction should reflect elevated fraud risk: " + response.fraudProbability());
    }

    @Test
    @DisplayName("4. Controlled Failure: Unavailable server produces controlled MlScoringException (CONNECTION_FAILED)")
    void shouldHandleUnavailableServerGracefully() {
        // Point client to a port where no server is listening
        SimpleClientHttpRequestFactory quickFailFactory = new SimpleClientHttpRequestFactory();
        quickFailFactory.setConnectTimeout(Duration.ofMillis(1000));
        quickFailFactory.setReadTimeout(Duration.ofMillis(1000));

        RestClient deadClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:65432")
                .requestFactory(quickFailFactory)
                .build();

        MlFraudScoringClient failingClient = new DefaultMlFraudScoringClient(deadClient);

        FraudPredictionRequest sampleRequest = new FraudPredictionRequest(
                BigDecimal.valueOf(100.00), 0, BigDecimal.valueOf(100.00), 1.0, 0, 0.0, 1.0
        );

        MlScoringException ex = assertThrows(MlScoringException.class, () ->
                failingClient.scoreTransaction(sampleRequest)
        );

        assertEquals(MlScoringException.ErrorCategory.CONNECTION_FAILED, ex.getCategory(),
                "Must map connection refusal to CONNECTION_FAILED category");
        assertNotNull(ex.getMessage(), "Exception must carry diagnostic reason");
    }
}
