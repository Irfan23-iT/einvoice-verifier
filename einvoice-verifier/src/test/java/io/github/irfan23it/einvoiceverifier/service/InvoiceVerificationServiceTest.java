package io.github.irfan23it.einvoiceverifier.service;

import io.github.irfan23it.einvoiceverifier.config.EinvoiceProperties;
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
import java.util.Arrays;
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

    private static final String VALID_TIN = "293847561012";

    private InvoiceVerificationService service;
    private InvoiceAuditRepository repository;
    private final Map<String, InvoiceAuditRecord> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(InvoiceAuditRepository.class);
        when(repository.findByInvoiceNumber(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        when(repository.save(any(InvoiceAuditRecord.class)))
            .thenAnswer(invocation -> {
                InvoiceAuditRecord record = invocation.getArgument(0);
                store.put(record.getInvoiceNumber(), record);
                return record;
            });

        service = serviceWith(defaultProperties());
    }

    // ------------------------------------------------------------------ fixtures

    private InvoiceVerificationService serviceWith(EinvoiceProperties properties) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return new InvoiceVerificationService(repository, validator, properties);
    }

    /** Nulls exercise the record's built-in defaults, i.e. the documented LHDN baseline. */
    private static EinvoiceProperties defaultProperties() {
        return new EinvoiceProperties(null, null);
    }

    private static EinvoiceProperties propertiesWithTinPattern(String tinPattern) {
        return new EinvoiceProperties(
            new EinvoiceProperties.Rules(tinPattern, null, null, 0), null);
    }

    private static EinvoiceProperties propertiesWithSstRates(String... rates) {
        List<BigDecimal> parsed = Arrays.stream(rates).map(BigDecimal::new).toList();
        return new EinvoiceProperties(new EinvoiceProperties.Rules(null, null, parsed, 0), null);
    }

    private static InvoiceRequest invoice(String number, String idType, String idValue, String sstRate,
                                          String lineTotal, String subtotal, String tax, String payable) {
        return new InvoiceRequest(
            number, "Syarikat Maju Jaya Sdn Bhd", idType, idValue, VALID_TIN,
            List.of(new LineItemRequest("Facility Fee", new BigDecimal("1"), new BigDecimal("1000.00"),
                new BigDecimal(sstRate), new BigDecimal(lineTotal))),
            new BigDecimal(subtotal), new BigDecimal(tax), new BigDecimal(payable));
    }

    private static InvoiceRequest validInvoice() {
        return invoice("INV-2026-001", "BRN", "202401009876", "0.08", "1080.00", "1000.00", "80.00", "1080.00");
    }

    private static InvoiceRequest withTin(InvoiceRequest source, String tin) {
        return new InvoiceRequest(source.invoiceNumber(), source.buyerName(), source.buyerIdType(),
            source.buyerIdValue(), tin, source.items(), source.subtotal(), source.totalTax(), source.totalPayable());
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
            invoice("INV-2026-002", "BRN", "1234567890", "0.08", "1080.00", "1000.00", "80.00", "1080.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("does not match the required identity format"));
        assertThat(response.lhdnUuid()).isNull();
    }

    @Test
    @DisplayName("A TIN that is not 12 digits is intercepted")
    void malformedTinIsRejected() {
        VerificationResponse response = service.verifyAndProcess(withTin(validInvoice(), "C2938475610"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errorCount()).isEqualTo(1);
        assertThat(response.errors()).anyMatch(e -> e.contains("Malaysian TIN format"));
    }

    @Test
    @DisplayName("A single-sen divergence in total payable is intercepted")
    void oneSenRoundingMismatchIsRejected() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-004", "BRN", "202401001234", "0.08", "1080.00", "1000.00", "80.00", "1080.01"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("Total Payable Mismatch"));
    }

    @Test
    @DisplayName("A line-level arithmetic defect is reported against the offending item")
    void lineTotalMismatchIsRejected() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-005", "BRN", "202401001234", "0.08", "1000.00", "1000.00", "80.00", "1080.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("Math Mismatch at Item 1"));
    }

    @Test
    @DisplayName("An unknown buyerIdType is rejected")
    void unknownIdTypeIsRejected() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-006", "PASSPORT", "202401001234", "0.08", "1080.00", "1000.00", "80.00", "1080.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errors()).anyMatch(e -> e.contains("Invalid buyerIdType"));
    }

    @Test
    @DisplayName("Half-up rounding is applied at sen level")
    void halfUpRoundingIsApplied() {
        InvoiceRequest request = new InvoiceRequest(
            "INV-2026-007", "Rounding Test Sdn Bhd", "BRN", "202401009876", VALID_TIN,
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
            "INV-2026-008", "Multi Item Sdn Bhd", "BRN", "202401009876", VALID_TIN,
            List.of(
                new LineItemRequest("Item A", BigDecimal.ONE, new BigDecimal("1000.00"), new BigDecimal("0.08"), new BigDecimal("1080.00")),
                new LineItemRequest("Item B", new BigDecimal("2"), new BigDecimal("500.00"), new BigDecimal("0.08"), new BigDecimal("1080.00"))
            ),
            new BigDecimal("2000.00"), new BigDecimal("160.00"), new BigDecimal("2160.00"));

        assertThat(service.verifyAndProcess(request).status()).isEqualTo("CERTIFIED_LHDN");
    }

    // ------------------------------------------------------------------ tax rules

    @Test
    @DisplayName("A service tax rate outside the approved schedule is rejected")
    void unapprovedSstRateIsRejected() {
        // Arithmetic is internally consistent at 10%, so the only defect is the rate itself.
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-011", "BRN", "202401001234", "0.10", "1100.00", "1000.00", "100.00", "1100.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(response.errorCount()).isEqualTo(1);
        assertThat(response.errors()).anyMatch(e -> e.contains("not an approved Malaysian SST rate"));
    }

    @Test
    @DisplayName("The 6% rate is accepted alongside 8%")
    void sixPercentRateIsAccepted() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("INV-2026-012", "BRN", "202401009876", "0.06", "1060.00", "1000.00", "60.00", "1060.00"));

        assertThat(response.status()).isEqualTo("CERTIFIED_LHDN");
    }

    // ------------------------------------------------------ configurable business rules

    @Test
    @DisplayName("The TIN rule honours a custom pattern")
    void tinRuleIsConfigurable() {
        InvoiceVerificationService permissive = serviceWith(propertiesWithTinPattern("^[A-Z][0-9]{10}$"));

        VerificationResponse response = permissive.verifyAndProcess(withTin(validInvoice(), "C2938475610"));

        assertThat(response.status()).isEqualTo("CERTIFIED_LHDN");
    }

    @Test
    @DisplayName("The approved SST schedule honours configuration")
    void sstScheduleIsConfigurable() {
        InvoiceVerificationService onlyEightPercent = serviceWith(propertiesWithSstRates("0.08"));

        VerificationResponse sixPercent = onlyEightPercent.verifyAndProcess(
            invoice("INV-2026-013", "BRN", "202401009876", "0.06", "1060.00", "1000.00", "60.00", "1060.00"));

        assertThat(sixPercent.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(sixPercent.errors()).anyMatch(e -> e.contains("not an approved Malaysian SST rate"));
    }

    // ------------------------------------------------------------- null-safety

    @Test
    @DisplayName("A payload with null amounts is rejected cleanly instead of throwing")
    void nullAmountsAreRejectedNotThrown() {
        InvoiceRequest request = new InvoiceRequest(
            "INV-2026-009", "Incomplete Sdn Bhd", "BRN", "202401009876", VALID_TIN,
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
            "INV-2026-010", "Empty Sdn Bhd", "BRN", "202401009876", VALID_TIN,
            List.of(), new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"));

        assertThat(service.verifyAndProcess(request).status()).isEqualTo("REJECTED_INTERNAL");
    }

    @Test
    @DisplayName("A blank invoice number is rejected and never persisted")
    void blankInvoiceNumberIsNotPersisted() {
        VerificationResponse response = service.verifyAndProcess(
            invoice("   ", "BRN", "202401009876", "0.08", "1080.00", "1000.00", "80.00", "1080.00"));

        assertThat(response.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(store).isEmpty();
    }

    // ---------------------------------------------------------- audit integrity

    @Test
    @DisplayName("Re-submitting an invoice refreshes the stored manifest rather than mixing attempts")
    void resubmissionRefreshesStoredManifest() {
        assertThat(service.verifyAndProcess(validInvoice()).status()).isEqualTo("CERTIFIED_LHDN");
        assertThat(store.get("INV-2026-001").getErrorManifest()).isEqualTo("NONE");
        assertThat(store.get("INV-2026-001").getAttemptCount()).isEqualTo(1);

        // Same invoice number, now defective.
        InvoiceRequest defective = invoice("INV-2026-001", "BRN", "202401009876", "0.08", "1080.00", "1000.00", "80.00", "1080.01");
        assertThat(service.verifyAndProcess(defective).status()).isEqualTo("REJECTED_INTERNAL");

        InvoiceAuditRecord stored = store.get("INV-2026-001");
        assertThat(stored.getStatus()).isEqualTo("REJECTED_INTERNAL");
        assertThat(stored.getErrorManifest()).contains("Total Payable Mismatch");
        assertThat(stored.getLhdnUuid()).isNull();
        assertThat(stored.getAttemptCount()).isEqualTo(2);
    }
}
