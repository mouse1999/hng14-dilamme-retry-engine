package com.mouse.dilamme.retry.controller;

import com.mouse.dilamme.retry.dto.CreateRetryRequestDto;
import com.mouse.dilamme.retry.enums.DeadLetterReason;
import com.mouse.dilamme.retry.enums.RequestStatus;
import com.mouse.dilamme.retry.model.RetryRequest;
import com.mouse.dilamme.retry.service.RequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RequestController {

    private final RequestService requestService;

    /**
     * POST /request
     * Accepts a structured JSON payload bound seamlessly into a request DTO.
     * Returns immediately with { "id": "...", "status": "PENDING" }
     */
    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody CreateRetryRequestDto payload) {
        if (payload.getUrl() == null || payload.getUrl().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "url is required"));
        }

        RetryRequest saved = requestService.create(payload);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "id", saved.getId(),
                        "status", saved.getStatus()
                ));
    }

    /**
     * GET /requests/:id — returns the request + full attempt history.
     */
    @GetMapping("/requests/{id}")
    public ResponseEntity<?> getById(@PathVariable UUID id) {
        return requestService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /requests?status=PENDING|RETRYING|COMPLETED|FAILED
     * Parses the query param into RequestStatus — returns 400 on invalid values.
     */
    @GetMapping("/requests")
    public ResponseEntity<?> listByStatus(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "status query param is required",
                            "valid", List.of(RequestStatus.values())));
        }
        try {
            RequestStatus requestStatus = RequestStatus.valueOf(status.toUpperCase());
            return ResponseEntity.ok(requestService.findByStatus(requestStatus));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid status: " + status,
                            "valid", List.of(RequestStatus.values())));
        }
    }

    /**
     * GET /dead-letter — all dead-letter entries, newest first.
     * Optional: ?reason=MAX_RETRIES_EXCEEDED | TERMINAL_4XX
     */
    @GetMapping("/dead-letter")
    public ResponseEntity<?> listDeadLetters(@RequestParam(required = false) String reason) {
        if (reason != null && !reason.isBlank()) {
            try {
                DeadLetterReason dlReason = DeadLetterReason.valueOf(reason.toUpperCase());
                return ResponseEntity.ok(requestService.findDeadLettersByReason(dlReason));
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid reason: " + reason,
                                "valid", List.of(DeadLetterReason.values())));
            }
        }
        return ResponseEntity.ok(requestService.findAllDeadLetters());
    }

    /**
     * GET /dead-letter/{requestId} — dead-letter entry for a specific original request ID.
     */
    @GetMapping("/dead-letter/{requestId}")
    public ResponseEntity<?> getDeadLetterByRequestId(@PathVariable UUID requestId) {
        return requestService.findDeadLetterByRequestId(requestId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity
                        .notFound()
                        .build());
    }
}