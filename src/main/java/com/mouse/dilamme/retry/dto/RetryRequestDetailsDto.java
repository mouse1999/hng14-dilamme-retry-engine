package com.mouse.dilamme.retry.dto;

import com.mouse.dilamme.retry.enums.RequestStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class RetryRequestDetailsDto {
    private UUID id;
    private String url;
    private String method;
    private String body;
    private RequestStatus status;
    private int attemptCount;
    private int maxRetries;
    private long backoffMs;
    private String result;
    private String lastError;
    private Instant nextRetryAt;
    private List<RetryAttemptResponseDto> attempts;
}