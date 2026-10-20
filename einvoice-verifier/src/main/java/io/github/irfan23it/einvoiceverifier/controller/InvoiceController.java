package io.github.irfan23it.einvoiceverifier.controller;

import io.github.irfan23it.einvoiceverifier.dto.BatchUploadResponse;
import io.github.irfan23it.einvoiceverifier.dto.InvoiceMetricsResponse;
import io.github.irfan23it.einvoiceverifier.dto.InvoiceRequest;
import io.github.irfan23it.einvoiceverifier.dto.VerificationResponse;
import io.github.irfan23it.einvoiceverifier.entity.InvoiceAuditRecord;
import io.github.irfan23it.einvoiceverifier.service.InvoiceBatchSanitizer;
import io.github.irfan23it.einvoiceverifier.service.InvoiceVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/einvoice")
public class InvoiceController {

    private final InvoiceVerificationService verificationService;
    private final InvoiceBatchSanitizer batchSanitizer;

    public InvoiceController(InvoiceVerificationService verificationService,
                             InvoiceBatchSanitizer batchSanitizer) {
        this.verificationService = verificationService;
        this.batchSanitizer = batchSanitizer;
    }

    @PostMapping("/verify")
    @Operation(summary = "Validate a single invoice payload",
        description = "Runs structural, statutory and sen-level arithmetic checks before a mock LHDN clearance.")
    public ResponseEntity<VerificationResponse> verify(@RequestBody InvoiceRequest request) {
        return ResponseEntity.ok(verificationService.verifyAndProcess(request));
    }

    @PostMapping(value = "/upload-batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Sanitise a CSV batch",
        description = "Scans every row of a multipart CSV file, one isolated transaction per row.")
    public ResponseEntity<BatchUploadResponse> uploadBatch(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No CSV file was uploaded.");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .csv billing files are accepted.");
        }

        try {
            return ResponseEntity.ok(batchSanitizer.processBatch(file));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/history")
    @Operation(summary = "Retrieve the audit trail",
        description = "Newest first, optionally filtered by verdict status.")
    public ResponseEntity<List<InvoiceAuditRecord>> getAuditHistory(
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(verificationService.getAuditRecords(status));
    }

    @GetMapping("/metrics")
    @Operation(summary = "Get audit metrics and KPI statistics",
        description = "Returns aggregated pre-flight validation metrics and defect counts.")
    public ResponseEntity<InvoiceMetricsResponse> getMetrics() {
        return ResponseEntity.ok(verificationService.getMetrics());
    }
}
