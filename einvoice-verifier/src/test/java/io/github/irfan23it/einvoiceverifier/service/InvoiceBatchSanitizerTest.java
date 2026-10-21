package io.github.irfan23it.einvoiceverifier.service;

import io.github.irfan23it.einvoiceverifier.config.EinvoiceProperties;
import io.github.irfan23it.einvoiceverifier.dto.BatchRowResult;
import io.github.irfan23it.einvoiceverifier.dto.BatchUploadResponse;
import io.github.irfan23it.einvoiceverifier.entity.InvoiceAuditRecord;
import io.github.irfan23it.einvoiceverifier.repository.InvoiceAuditRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvoiceBatchSanitizerTest {

    private static final String HEADER =
        "invoiceNumber,buyerName,buyerIdType,buyerIdValue,buyerTin,desc,qty,price,sstRate,lineTotal,subtotal,totalTax,totalPayable";

    private static final String CLEAN_ROW =
        "INV-2026-001,Maju Holdings Sdn Bhd,BRN,202401001111,102938475010,Implementation Fee,1,2000.00,0.08,2160.00,2000.00,160.00,2160.00";

    private final CsvInvoiceBatchParser parser = new CsvInvoiceBatchParser();
    private final Map<String, InvoiceAuditRecord> store = new HashMap<>();
    private InvoiceAuditRepository repository;
    private InvoiceBatchSanitizer sanitizer;

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
        sanitizer = sanitizerWith(new EinvoiceProperties(null, null));
    }

    private InvoiceBatchSanitizer sanitizerWith(EinvoiceProperties properties) {
        InvoiceVerificationService verification = new InvoiceVerificationService(
            repository, Validation.buildDefaultValidatorFactory().getValidator(), properties);
        return new InvoiceBatchSanitizer(parser, verification, properties);
    }

    private static MockMultipartFile csv(String... rows) {
        String content = HEADER + "\n" + String.join("\n", rows) + "\n";
        return new MockMultipartFile("file", "billing-batch.csv", "text/csv",
            content.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------- parser

    @Test
    @DisplayName("A well-formed row materialises into an invoice request")
    void wellFormedRowIsParsed() {
        List<CsvInvoiceBatchParser.ParsedRow> rows = parser.parse(csv(CLEAN_ROW));

        assertThat(rows).hasSize(1);
        CsvInvoiceBatchParser.ParsedRow row = rows.get(0);
        assertThat(row.rowNumber()).isEqualTo(1);
        assertThat(row.parseErrors()).isEmpty();
        assertThat(row.request()).isNotNull();
        assertThat(row.request().items()).hasSize(1);
    }

    @Test
    @DisplayName("Quoted fields containing commas and line-level blanks are handled")
    void quotedAndBlankFieldsAreHandled() {
        List<CsvInvoiceBatchParser.ParsedRow> rows = parser.parse(csv(
            "INV-2026-002,\"Jaya, Koperasi Berhad\",BRN,1234567890,,Support Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.00"));

        assertThat(rows.get(0).buyerName()).isEqualTo("Jaya, Koperasi Berhad");
        assertThat(rows.get(0).buyerTin()).isNull();
    }

    @Test
    @DisplayName("A non-numeric cell is reported without aborting the file")
    void nonNumericCellIsReported() {
        List<CsvInvoiceBatchParser.ParsedRow> rows = parser.parse(csv(
            "INV-2026-003,Broken Sdn Bhd,BRN,202401001234,112233445566,Fee,1,not-a-number,0.08,1080.00,1000.00,80.00,1080.00"));

        CsvInvoiceBatchParser.ParsedRow row = rows.get(0);
        assertThat(row.request()).isNull();
        assertThat(row.parseErrors()).anyMatch(e -> e.contains("'price' is not a valid number"));
    }

    @Test
    @DisplayName("A file missing required columns is rejected outright")
    void missingColumnsAreRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "bad.csv", "text/csv",
            "invoiceNumber,buyerName\nINV-1,Acme\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required column");
    }

    // --------------------------------------------------------------------- batch

    @Test
    @DisplayName("A mixed batch splits into certified and intercepted rows")
    void mixedBatchIsSanitized() {
        BatchUploadResponse response = sanitizer.processBatch(csv(
            CLEAN_ROW,
            "INV-2026-002,Koperasi Jaya,BRN,1234567890,998877665544,Support Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.00",
            "INV-2026-003,Syarikat Maju Jaya Sdn Bhd,BRN,202401009876,293847561012,Financing Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.00",
            "INV-2026-004,Defective Trading Enterprise,BRN,202401001234,112233445566,Retainer Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.01"));

        assertThat(response.totalRows()).isEqualTo(4);
        assertThat(response.certified()).isEqualTo(2);
        assertThat(response.rejected()).isEqualTo(2);

        BatchRowResult legacyBrn = response.rows().get(1);
        assertThat(legacyBrn.status()).isEqualTo("REJECTED_INTERNAL");
        assertThat(legacyBrn.errors()).anyMatch(e -> e.contains("does not match the required identity format"));

        BatchRowResult oneSen = response.rows().get(3);
        assertThat(oneSen.errors()).anyMatch(e -> e.contains("Total Payable Mismatch"));
    }

    @Test
    @DisplayName("Malformed rows are persisted as rejections alongside clean rows")
    void malformedRowsArePersisted() {
        sanitizer.processBatch(csv(
            CLEAN_ROW,
            "INV-2026-009,Broken Sdn Bhd,BRN,202401001234,112233445566,Fee,1,oops,0.08,1080.00,1000.00,80.00,1080.00"));

        assertThat(store).containsKeys("INV-2026-001", "INV-2026-009");
        assertThat(store.get("INV-2026-009").getStatus()).isEqualTo("REJECTED_INTERNAL");
        assertThat(store.get("INV-2026-009").getErrorManifest()).contains("is not a valid number");
    }

    @Test
    @DisplayName("Rows without an invoice number are reported but not persisted")
    void rowsWithoutInvoiceNumberAreNotPersisted() {
        BatchUploadResponse response = sanitizer.processBatch(csv(
            ",Nameless Sdn Bhd,BRN,202401001234,112233445566,Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.00"));

        assertThat(response.rejected()).isEqualTo(1);
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("A batch larger than the configured limit is rejected before processing")
    void batchExceedingRowLimitIsRejected() {
        InvoiceBatchSanitizer limited = sanitizerWith(
            new EinvoiceProperties(new EinvoiceProperties.Rules(null, null, null, 1), null));

        assertThatThrownBy(() -> limited.processBatch(csv(
            CLEAN_ROW,
            "INV-2026-003,Syarikat Maju Jaya Sdn Bhd,BRN,202401009876,293847561012,Financing Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeds the configured limit of 1");

        assertThat(store).isEmpty();
    }
}
