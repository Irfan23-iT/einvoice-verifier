package io.github.irfan23it.einvoiceverifier.service;

import io.github.irfan23it.einvoiceverifier.dto.InvoiceRequest;
import io.github.irfan23it.einvoiceverifier.dto.LineItemRequest;
import io.github.irfan23it.einvoiceverifier.dto.VerificationResponse;
import io.github.irfan23it.einvoiceverifier.entity.InvoiceAuditRecord;
import io.github.irfan23it.einvoiceverifier.repository.InvoiceAuditRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvoiceVerificationServiceTest {

    private InvoiceVerificationService service;
    private final Map<String, InvoiceAuditRecord> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        InvoiceAuditRepository repository = mock(InvoiceAuditRepository.class);
        when(repository.findByInvoiceNumber(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        when(repository.save(any(InvoiceAuditRecord.class)))
            .thenAnswer(invocation -> {
                InvoiceAuditRecord record = invocation.getArgument(0);
                store.put(record.getInvoiceNumber(), record);
                return record;
            });

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        service = new InvoiceVerificationService(repository, new CsvInvoiceBatchParser(), validator);
    }

    // ------------------------------------------------------------------ fixtures

    private static InvoiceRequest invoice(String invoiceNumber, String buyerIdType, String buyerIdValue,
                                          String lineTotal, String subtotal, String totalTax, String totalPayable) {
        return new InvoiceRequest(
            invoiceNumber, "Syarikat Maju Jaya Sdn Bhd", buyerIdType, buyerIdValue, "C2938475610",
            List.of(new LineItemRequest("Facility Fee", new BigDecimal("1"), new BigDecimal("1000.00"),
                new BigDecimal("0.08"), new BigDecimal(lineTotal))),
            new BigDecimal(subtotal), new BigDecimal(totalTax), new BigDecimal(totalPayable));
    }

    private static InvoiceRequest validInvoice() {
        return invoice("INV-2026-001", "BRN", "202401009876", "1080.00", "1000.00", "80.00", "1080.00");
    }

    // ------------------------------------------------------------------- verdicts

    @Test
    @DisplayName("A clean payload is certified with a mock LHDN UUID and QR URL")
    void cleanInvoiceIsCertified() {
        VerificationResponse response = service.verifyAndProcess(validInvoice());

        assertThat(response.status()).isEqualTo("CERTIFIED_LHDN");
        assertThat(response.errorCount()).isZero();
        assertThat(response.errors()).isEmpty();
        assertThat(response.lhdnUuid()).startsWith("LHDN-MYINVOIS-");
        assertThat(response.qrCodeUrl()).contains(response.lhdnUuid());
        assertThat(store.get("INV-2026-001").getStatus()).isEqualTo("CERTIFIED_LHDN");
    }

    @Test
    @DisplayName("A 10-digit BRN is intercepted before gateway submission")
    void legacyTenDigitBrnIsRejected() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-002", "BRN", "1234567890", "1080.00", "1000.00", "80.00", "1080.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("exactly 12 numeric digits"));
        assertThat(response.lhdnUuid()).isNull();
    }

    @Test
    @DisplayName("A single-sen divergence in total payable is intercepted")
    void oneSenRoundingMismatchIsRejected() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-004", "BRN", "202401001234", "1080.00", "1000.00", "80.00", "1080.01"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("Total Payable Mismatch"));
    }

    @Test
    @DisplayName("A line-level arithmetic defect is reported against the offending item")
    void lineTotalMismatchIsRejected() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-005", "BRN", "202401001234", "1000.00", "1000.00", "80.00", "1080.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("Math Mismatch at Item 1"));
    }

    @Test
    @DisplayName("An unknown buyerIdType is rejected")
    void unknownIdTypeIsRejected() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-006", "PASSPORT", "202401001234", "1080.00", "1000.00", "80.00", "1080.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("Invalid buyerIdType"));
    }

    @Test
    @DisplayName("Half-up rounding is applied at sen level")
    void halfUpRoundingIsApplied() {
        InvoiceRequest request = new InvoiceRequest(
            "INV-2026-007", "Rounding Test Sdn Bhd", "BRN", "202401009876", "C2938475610",
            List.of(new LineItemRequest("Micro Fee", BigDecimal.ONE, new BigDecimal("0.125"),
                new BigDecimal("0.08"), new BigDecimal("0.14"))),
            new BigDecimal("0.13"), new BigDecimal("0.01"), new BigDecimal("0.14"));

        // 0.125 -> 0.13 (HALF_UP), tax 0.0104 -> 0.01, total 0.14
        assertThat(service.verifyAndProcess(request).status()).isEqualTo("CERTIFIED_LHDN");
    }

    @Test
    @DisplayName("Totals are the sum of line values across multiple items")
    void multiItemTotalsAreSummed() {
        InvoiceRequest request = new InvoiceRequest(
            "INV-2026-008", "Multi Item Sdn Bhd", "BRN", "202401009876", "C2938475610",
            List.of(
                new LineItemRequest("Item A", BigDecimal.ONE, new BigDecimal("1000.00"), new BigDecimal("0.08"), new BigDecimal("1080.00")),
                new LineItemRequest("Item B", new BigDecimal("2"), new BigDecimal("500.00"), new BigDecimal("0.08"), new BigDecimal("1080.00"))
            ),
            new BigDecimal("2000.00"), new BigDecimal("160.00"), new BigDecimal("2160.00"));

        assertThat(service.verifyAndProcess(request).status()).isEqualTo("CERTIFIED_LHDN");
    }

    // ------------------------------------------------------------- null-safety

    @Test
    @DisplayName("A payload with null amounts is rejected cleanly instead of throwing")
    void nullAmountsAreRejectedNotThrown() {
        InvoiceRequest request = new InvoiceRequest(
            "INV-2026-009", "Incomplete Sdn Bhd", "BRN", "202401009876", "C2938475610",
            List.of(new LineItemRequest("Fee", BigDecimal.ONE, new BigDecimal("1000.00"), new BigDecimal("0.08"), new BigDecimal("1080.00"))),
            null, null, null);

        VerificationResponse response = service.verifyAndProcess(request);

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("subtotal"));
    }

    @Test
    @DisplayName("A payload with no line items is rejected")
    void emptyItemsAreRejected() {
        InvoiceRequest request = new InvoiceRequest(
            "INV-2026-010", "Empty Sdn Bhd", "BRN", "202401009876", "C2938475610",
            List.of(), new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"));

        assertThat(service.verifyAndProcess(request).status()).isEqualTo("REJECTED_INTERNAL");
    }

    @Test
    @DisplayName("A blank invoice number is rejected and never persisted")
    void blankInvoiceNumberIsNotPersisted() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("   ", "BRN", "202401009876", "1080.00", "1000.00", "80.00", "1080.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(store).isEmpty();
    }

    // ---------------------------------------------------------- audit integrity

    @Test
    @DisplayName("Re-submitting an invoice refreshes the stored manifest rather than mixing attempts")
    void resubmissionRefreshesStoredManifest() {
        InvoiceRequest clean = validInvoice();
        assertThat(service.verifyAndProcess(clean).status()).isEqualTo("CERTIFIED_LHDN");
        assertThat(store.get("INV-2026-001").getErrorManifest()).isEqualTo("NONE");
        assertThat(store.get("INV-2026-001").getAttemptCount()).isEqualTo(1);

        // Same invoice number, now defective.
        InvoiceRequest defective = invoice("INV-2026-001", "BRN", "202401009876", "1080.00", "1000.00", "80.00", "1080.01");
        assertThat(service.verifyAndProcess(defective).status()).isEqualTo("REJECTED_INTERNAL");

        InvoiceAuditRecord stored = store.get("INV-2026-001");
        assertThat(stored.getStatus()).isEqualTo("REJECTED_INTERNAL");
        assertThat(stored.getErrorManifest()).contains("Total Payable Mismatch");
        assertThat(stored.getLhdnUuid()).isNull();
        assertThat(stored.getAttemptCount()).isEqualTo(2);
    }
}
