package com.mouse.dilamme.retry.dto;

import com.mouse.dilamme.retry.enums.AttemptOutcome;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class RetryAttemptResponseDto {
    private UUID id;
    private int attemptNumber;
    private Instant attemptedAt;
    private Integer statusCode;
    private AttemptOutcome outcome;
    private String errorMessage;
    private long waitedMs;
}