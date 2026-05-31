package com.mouse.dilamme.retry.model;

import com.mouse.dilamme.retry.enums.AttemptOutcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private RetryRequest request;

    @Column(nullable = false)
    private int attemptNumber;

    @Column(nullable = false)
    @Builder.Default
    private Instant attemptedAt = Instant.now();

    /**
     * HTTP status code returned. Null if a network error / timeout occurred
     * (i.e. we never got a response).
     */
    private Integer statusCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptOutcome outcome;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Actual jittered wait (ms) applied before this attempt. 0 for the first attempt.
     */
    @Column(nullable = false)
    @Builder.Default
    private long waitedMs = 0L;
}