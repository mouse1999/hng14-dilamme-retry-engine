package com.mouse.dilamme.retry.model;

import com.mouse.dilamme.retry.enums.RequestStatus;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetryRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String method;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RequestStatus status = RequestStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private int maxRetries = 5;

    @Column(nullable = false)
    @Builder.Default
    private long backoffMs = 1000L;

    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Instant nextRetryAt = Instant.now();

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("attemptNumber ASC")
    @Builder.Default
    private List<RetryAttempt> attempts = new ArrayList<>();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}