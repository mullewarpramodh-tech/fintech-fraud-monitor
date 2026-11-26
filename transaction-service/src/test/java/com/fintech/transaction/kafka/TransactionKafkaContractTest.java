package com.fintech.transaction.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.transaction.model.Transaction;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Cross-Service Kafka Payload Contract & Serialization Tests")
class TransactionKafkaContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("P2: Serializes Transaction entity according to production configuration and verifies Fraud Consumer compatibility")
    void shouldSerializeTransactionMatchingFraudConsumerContract() throws Exception {
        // 1. Arrange: Create a real Transaction entity with typical values
        Transaction tx = new Transaction();
        tx.setId(88421L);
        tx.setAccountId("ACC-CONTRACT-99");
        tx.setAmount(BigDecimal.valueOf(1250.75));
        tx.setCurrency("USD");
        tx.setStatus("PENDING");
        tx.setTimestamp(LocalDateTime.of(2026, 11, 24, 15, 30, 0));

        // 2. Configure production JsonSerializer (spring.json.add.type.headers: false)
        JsonSerializer<Transaction> serializer = new JsonSerializer<>();
        serializer.configure(Map.of(
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false
        ), false);

        Headers headers = new RecordHeaders();
        byte[] payloadBytes = serializer.serialize("transaction-events", headers, tx);

        assertNotNull(payloadBytes, "Serialized payload must not be null");
        assertTrue(payloadBytes.length > 0, "Serialized payload must contain data");

        // 3. Verify: Type headers MUST be disabled as configured in production
        assertNull(headers.lastHeader("__TypeId__"),
                "Production configuration disables type headers to prevent tight cross-service classpath coupling");
        assertEquals(0, headers.toArray().length, "No type info headers should be appended");

        // 4. Verify raw JSON structure and expected field names
        String jsonString = new String(payloadBytes, StandardCharsets.UTF_8);
        JsonNode jsonNode = objectMapper.readTree(jsonString);

        assertTrue(jsonNode.hasNonNull("id"), "Contract requires 'id' attribute");
        assertTrue(jsonNode.hasNonNull("accountId"), "Contract requires 'accountId' attribute");
        assertTrue(jsonNode.hasNonNull("amount"), "Contract requires 'amount' attribute");
        assertTrue(jsonNode.hasNonNull("currency"), "Contract requires 'currency' attribute");
        assertTrue(jsonNode.hasNonNull("status"), "Contract requires 'status' attribute");
        assertTrue(jsonNode.hasNonNull("timestamp"), "Contract requires 'timestamp' attribute");

        assertEquals(88421L, jsonNode.get("id").asLong());
        assertEquals("ACC-CONTRACT-99", jsonNode.get("accountId").asText());
        assertEquals(1250.75, jsonNode.get("amount").asDouble(), 0.001);
        assertEquals("USD", jsonNode.get("currency").asText());
        assertEquals("PENDING", jsonNode.get("status").asText());

        // 5. Test consumer deserialization using fraud-service's exact configuration:
        //    value-deserializer: JsonDeserializer
        //    spring.json.use.type.headers: false
        //    spring.json.value.default.type: java.util.Map
        @SuppressWarnings("rawtypes")
        JsonDeserializer<Map> deserializer = new JsonDeserializer<>();
        deserializer.configure(Map.of(
                JsonDeserializer.TRUSTED_PACKAGES, "*",
                JsonDeserializer.USE_TYPE_INFO_HEADERS, false,
                JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map"
        ), false);

        @SuppressWarnings("unchecked")
        Map<String, Object> deserializedMap = deserializer.deserialize("transaction-events", headers, payloadBytes);

        assertNotNull(deserializedMap, "Consumer deserializer must successfully parse payload");

        // 6. Verify FraudDetectionConsumer.extractTransactionId() compatibility
        Object idObj = deserializedMap.get("id");
        assertNotNull(idObj, "Consumer requires non-null 'id'");

        Long extractedId;
        if (idObj instanceof Number number) {
            extractedId = number.longValue();
        } else {
            extractedId = Long.parseLong(idObj.toString().trim());
        }
        assertEquals(88421L, extractedId, "Extracted ID must match original transaction ID");

        // 7. Verify currency, account, and amount values
        assertEquals("ACC-CONTRACT-99", deserializedMap.get("accountId"));
        assertEquals("USD", deserializedMap.get("currency"));
        assertEquals("PENDING", deserializedMap.get("status"));

        BigDecimal parsedAmount = new BigDecimal(deserializedMap.get("amount").toString());
        assertEquals(0, BigDecimal.valueOf(1250.75).compareTo(parsedAmount),
                "Parsed amount must preserve numeric precision");

        serializer.close();
        deserializer.close();
    }
}
