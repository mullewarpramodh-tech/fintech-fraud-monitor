package com.fintech.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO for user login authentication requests.
 * Ignores any client-supplied role to prevent unauthorized privilege escalation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(
    String username,
    String password
) {}
