package com.fintech.fraud.client;

import com.fintech.fraud.dto.FraudPredictionRequest;
import com.fintech.fraud.dto.FraudPredictionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * Spring RestClient wrapper for the Python ML fraud scoring service.
 * Handles timeouts, network failures, HTTP errors, and response validation.
 * Does not make business decisions; surfaces structured exceptions to the Decision Engine.
 */
@Component
public class DefaultMlFraudScoringClient implements MlFraudScoringClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultMlFraudScoringClient.class);
    private final RestClient restClient;

    public DefaultMlFraudScoringClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public FraudPredictionResponse scoreTransaction(FraudPredictionRequest request) {
        try {
            FraudPredictionResponse response = restClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new MlScoringException(
                                MlScoringException.ErrorCategory.SERVER_ERROR,
                                "ML service returned HTTP " + resp.getStatusCode().value()
                        );
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        throw new MlScoringException(
                                MlScoringException.ErrorCategory.CLIENT_ERROR,
                                "ML service rejected feature payload with HTTP " + resp.getStatusCode().value()
                        );
                    })
                    .body(FraudPredictionResponse.class);

            if (response == null) {
                throw new MlScoringException(
                        MlScoringException.ErrorCategory.INVALID_RESPONSE,
                        "ML service returned empty/null response body"
                );
            }

            // Enforce probability bounds [0.0, 1.0] and non-empty version
            response.validate();

            log.info("ML scoring completed: fraud_probability={}, model_version={}",
                    response.fraudProbability(), response.modelVersion());
            return response;

        } catch (MlScoringException e) {
            log.warn("ML service invocation failed [{}]: {}", e.getCategory(), e.getMessage());
            throw e;

        } catch (ResourceAccessException e) {
            Throwable root = getRootCause(e);
            if (root instanceof SocketTimeoutException) {
                log.warn("ML service request timed out: {}", e.getMessage());
                throw new MlScoringException(
                        MlScoringException.ErrorCategory.TIMEOUT,
                        "ML service request timed out",
                        e
                );
            } else if (root instanceof ConnectException) {
                log.warn("ML service connection refused: {}", e.getMessage());
                throw new MlScoringException(
                        MlScoringException.ErrorCategory.CONNECTION_FAILED,
                        "ML service connection refused or unreachable",
                        e
                );
            } else {
                log.warn("ML service network communication failed: {}", e.getMessage());
                throw new MlScoringException(
                        MlScoringException.ErrorCategory.CONNECTION_FAILED,
                        "ML service communication failure: " + e.getMessage(),
                        e
                );
            }

        } catch (IllegalArgumentException e) {
            log.warn("ML response validation failed: {}", e.getMessage());
            throw new MlScoringException(
                    MlScoringException.ErrorCategory.INVALID_RESPONSE,
                    "Invalid ML response payload: " + e.getMessage(),
                    e
            );

        } catch (Exception e) {
            log.error("Unexpected error during ML fraud scoring: {}", e.getMessage(), e);
            throw new MlScoringException(
                    MlScoringException.ErrorCategory.UNEXPECTED,
                    "Unexpected error during ML scoring: " + e.getMessage(),
                    e
            );
        }
    }

    private Throwable getRootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
