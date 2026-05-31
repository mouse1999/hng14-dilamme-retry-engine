package com.mouse.dilamme.retry.worker;

import com.mouse.dilamme.retry.enums.AttemptOutcome;
import com.mouse.dilamme.retry.enums.DeadLetterReason;
import com.mouse.dilamme.retry.enums.RequestStatus;
import com.mouse.dilamme.retry.model.DeadLetterEntry;
import com.mouse.dilamme.retry.model.RetryAttempt;
import com.mouse.dilamme.retry.model.RetryRequest;
import com.mouse.dilamme.retry.repository.DeadLetterRepository;
import com.mouse.dilamme.retry.repository.RetryAttemptRepository;
import com.mouse.dilamme.retry.repository.RetryRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryWorker {

    private final RetryRequestRepository requestRepo;
    private final RetryAttemptRepository attemptRepo;
    private final DeadLetterRepository deadLetterRepo;
    private final RestTemplate restTemplate;

    /**
     * Wake every 500ms. Pick up all rows where nextRetryAt <= now()
     * and status is still actionable (PENDING or RETRYING).
     */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void processDueRequests() {
        List<RetryRequest> due = requestRepo.findDueRequests(
                Instant.now(), RequestStatus.PENDING, RequestStatus.RETRYING);
        for (RetryRequest req : due) {
            processOne(req);
        }
    }

    private void processOne(RetryRequest req) {
        int previousAttempts = req.getAttemptCount();
        int currentAttemptNumber = previousAttempts + 1;
        long waitedMs = computeWait(req.getBackoffMs(), previousAttempts);

        log.info("[{}] Attempt #{} — waited {}ms (backoffMs={}, currentAttemptCount={})",
                req.getId(), currentAttemptNumber, waitedMs, req.getBackoffMs(), previousAttempts);

        req.setStatus(RequestStatus.RETRYING);
        req.setAttemptCount(currentAttemptNumber);

        // Build the base retry attempt object using Lombok's Builder
        RetryAttempt.RetryAttemptBuilder attemptBuilder = RetryAttempt.builder()
                .request(req)
                .attemptNumber(currentAttemptNumber)
                .attemptedAt(Instant.now())
                .waitedMs(waitedMs);

        try {
            ResponseEntity<String> response = executeRequest(req);
            int statusCode = response.getStatusCode().value();
            attemptBuilder.statusCode(statusCode);

            if (response.getStatusCode().is2xxSuccessful()) {
                handleSuccess(req, currentAttemptNumber, response.getBody(), statusCode, attemptBuilder);
            } else if (response.getStatusCode().is4xxClientError()) {
                handleTerminalFailure(req, currentAttemptNumber, DeadLetterReason.TERMINAL_4XX, "HTTP " + statusCode, statusCode, attemptBuilder);
            } else {
                handleRetryableFailure(req, currentAttemptNumber, "HTTP " + statusCode, statusCode, attemptBuilder);
            }

        } catch (HttpClientErrorException ex) {
            int statusCode = ex.getStatusCode().value();
            handleTerminalFailure(req, currentAttemptNumber, DeadLetterReason.TERMINAL_4XX, "HTTP " + statusCode + ": " + ex.getMessage(), statusCode, attemptBuilder);
        } catch (HttpServerErrorException ex) {
            int statusCode = ex.getStatusCode().value();
            handleRetryableFailure(req, currentAttemptNumber, "HTTP " + statusCode + ": " + ex.getMessage(), statusCode, attemptBuilder);
        } catch (ResourceAccessException ex) {
            handleRetryableFailure(req, currentAttemptNumber, "Network error: " + ex.getMessage(), null, attemptBuilder);
        }

        // Build the finalized entity instance
        RetryAttempt attempt = attemptBuilder.build();

        // Post-execution: Process dead-letter logic if request is still marked for retry but hit max cap
        if (RequestStatus.RETRYING.equals(req.getStatus()) && req.getAttemptCount() >= req.getMaxRetries()) {
            req.setStatus(RequestStatus.FAILED);
            req.setLastError("Max retries (" + req.getMaxRetries() + ") reached. " + req.getLastError());
            moveToDeadLetter(req, DeadLetterReason.MAX_RETRIES_EXCEEDED, req.getLastError());
            log.error("[{}] Dead-lettered after {} attempts — moved to dead_letter table",
                    req.getId(), req.getAttemptCount());
        }

        attemptRepo.save(attempt);
        requestRepo.save(req);
    }

    private ResponseEntity<String> executeRequest(RetryRequest req) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(req.getBody(), headers);
        HttpMethod method = HttpMethod.valueOf(req.getMethod());
        return restTemplate.exchange(req.getUrl(), method, entity, String.class);
    }

    private void handleSuccess(RetryRequest req, int attemptNumber, String body, int statusCode, RetryAttempt.RetryAttemptBuilder attemptBuilder) {
        attemptBuilder.outcome(AttemptOutcome.SUCCESS);
        req.setStatus(RequestStatus.COMPLETED);
        req.setResult(body);
        log.info("[{}] Completed successfully on attempt #{} (HTTP {})",
                req.getId(), attemptNumber, statusCode);
    }

    private void handleTerminalFailure(RetryRequest req, int attemptNumber, DeadLetterReason reason, String errorDetails, int statusCode, RetryAttempt.RetryAttemptBuilder attemptBuilder) {
        attemptBuilder.outcome(AttemptOutcome.TERMINAL_ERROR)
                .errorMessage(errorDetails);

        req.setStatus(RequestStatus.FAILED);
        req.setLastError(errorDetails);

        moveToDeadLetter(req, reason, errorDetails);
        log.warn("[{}] Terminal failure ({}) on attempt #{} — moved to dead-letter, will not retry",
                req.getId(), statusCode, attemptNumber);
    }

    private void handleRetryableFailure(RetryRequest req, int attemptNumber, String errorDetails, Integer statusCode, RetryAttempt.RetryAttemptBuilder attemptBuilder) {
        attemptBuilder.outcome(AttemptOutcome.RETRYABLE_ERROR)
                .statusCode(statusCode)
                .errorMessage(errorDetails);

        long nextWait = computeWait(req.getBackoffMs(), req.getAttemptCount());
        req.setNextRetryAt(Instant.now().plusMillis(nextWait));
        req.setLastError(errorDetails);

        log.warn("[{}] Retryable error (HTTP {}) on attempt #{} — next retry scheduled in {}ms at {}",
                req.getId(), statusCode != null ? statusCode : "N/A", attemptNumber, nextWait, req.getNextRetryAt());
    }

    private void moveToDeadLetter(RetryRequest req, DeadLetterReason reason, String lastError) {
        if (deadLetterRepo.findByRequestId(req.getId()).isPresent()) {
            return;
        }

        DeadLetterEntry entry = DeadLetterEntry.builder()
                .requestId(req.getId())
                .url(req.getUrl())
                .method(req.getMethod())
                .body(req.getBody())
                .reason(reason)
                .totalAttempts(req.getAttemptCount())
                .lastError(lastError)
                .originalCreatedAt(req.getCreatedAt())
                .build();

        deadLetterRepo.save(entry);
    }

    /**
     * Pure backoff calculation — no side effects, easy to unit-test.
     */
    static long computeWait(long backoffMs, int attemptCount) {
        if (attemptCount == 0) return 0L;
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble(0.4); // [0.8, 1.2)
        long base = backoffMs * (1L << (attemptCount - 1));                // backoffMs × 2^(n-1)
        return Math.round(base * jitter);
    }
}
