package io.github.irfan23it.einvoiceverifier.service;

import io.github.irfan23it.einvoiceverifier.dto.BatchRowResult;
import io.github.irfan23it.einvoiceverifier.dto.BatchUploadResponse;
import io.github.irfan23it.einvoiceverifier.dto.InvoiceRequest;
import io.github.irfan23it.einvoiceverifier.dto.LineItemRequest;
import io.github.irfan23it.einvoiceverifier.dto.VerificationResponse;
import io.github.irfan23it.einvoiceverifier.entity.InvoiceAuditRecord;
import io.github.irfan23it.einvoiceverifier.repository.InvoiceAuditRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class InvoiceVerificationService {

    private static final Pattern NUMERIC_12_DIGIT = Pattern.compile("^[0-9]{12}$");
    private static final String STATUS_REJECTED = "REJECTED_INTERNAL";
    private static final String STATUS_CERTIFIED = "CERTIFIED_LHDN";

    private final InvoiceAuditRepository repository;
    private final CsvInvoiceBatchParser csvParser;
    private final Validator validator;

    public InvoiceVerificationService(InvoiceAuditRepository repository,
                                      CsvInvoiceBatchParser csvParser,
                                      Validator validator) {
        this.repository = repository;
        this.csvParser = csvParser;
        this.validator = validator;
    }

    @Transactional
    public VerificationResponse verifyAndProcess(InvoiceRequest invoice) {
        List<String> errors = new ArrayList<>();

        // 1. Declarative structural validation (presence, non-negativity, item shape).
        for (ConstraintViolation<InvoiceRequest> violation : validator.validate(invoice)) {
            errors.add(violation.getPropertyPath() + ": " + violation.getMessage());
        }

        // 2. Identity / format rules. Guarded so that a structurally invalid payload never
        //    triggers a NullPointerException further down.
        if (notBlank(invoice.buyerIdType())) {
            if ("BRN".equalsIgnoreCase(invoice.buyerIdType()) || "NRIC".equalsIgnoreCase(invoice.buyerIdType())) {
                if (invoice.buyerIdValue() == null
                        || !NUMERIC_12_DIGIT.matcher(invoice.buyerIdValue().trim()).matches()) {
                    errors.add("Format Error: " + invoice.buyerIdType()
                        + " must be exactly 12 numeric digits (Received: " + invoice.buyerIdValue() + ")");
                }
            } else {
                errors.add("Invalid buyerIdType: Must be either 'BRN' or 'NRIC'.");
            }
        }

        // 3. Sen-level arithmetic. Only attempted once every line item and header amount is
        //    present, otherwise the comparison would report spurious mismatches.
        List<LineItemRequest> items = invoice.items();
        if (items != null && !items.isEmpty() && itemsComplete(items)) {
            BigDecimal calculatedSubtotal = BigDecimal.ZERO;
            BigDecimal calculatedTotalTax = BigDecimal.ZERO;

            for (int i = 0; i < items.size(); i++) {
                LineItemRequest item = items.get(i);

                BigDecimal lineBase = item.quantity().multiply(item.unitPrice()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal lineTax = lineBase.multiply(item.sstRate()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal expectedLineTotal = lineBase.add(lineTax);

                if (item.lineTotal().compareTo(expectedLineTotal) != 0) {
                    errors.add("Math Mismatch at Item " + (i + 1) + " (" + item.description() + "): lineTotal submitted as "
                        + item.lineTotal() + ", but calculated as " + expectedLineTotal);
                }

                calculatedSubtotal = calculatedSubtotal.add(lineBase);
                calculatedTotalTax = calculatedTotalTax.add(lineTax);
            }

            BigDecimal expectedPayable = calculatedSubtotal.add(calculatedTotalTax);
            if (headerComplete(invoice)) {
                if (invoice.subtotal().compareTo(calculatedSubtotal) != 0) {
                    errors.add("Subtotal Mismatch: Submitted " + invoice.subtotal() + ", Expected " + calculatedSubtotal);
                }
                if (invoice.totalTax().compareTo(calculatedTotalTax) != 0) {
                    errors.add("Total Tax Mismatch: Submitted " + invoice.totalTax() + ", Expected " + calculatedTotalTax);
                }
                if (invoice.totalPayable().compareTo(expectedPayable) != 0) {
                    errors.add("Total Payable Mismatch: Submitted " + invoice.totalPayable() + ", Expected " + expectedPayable);
                }
            }
        }

        if (!errors.isEmpty()) {
            return reject(invoice, errors);
        }

        // 4. Simulated LHDN MyInvois clearance gateway call.
        String lhdnUuid = "LHDN-MYINVOIS-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        String qrCodeUrl = "https://myinvois.hasil.gov.my/taxpayer/validate/" + lhdnUuid;

        persist(invoice, STATUS_CERTIFIED, "NONE", lhdnUuid, qrCodeUrl);
        return VerificationResponse.certified(invoice.invoiceNumber(), lhdnUuid, qrCodeUrl);
    }

    /**
     * Sanitizes an uploaded CSV dump, running every row through the same rules as a
     * single submission. Malformed rows are persisted as rejections rather than aborting the batch.
     */
    @Transactional
    public BatchUploadResponse processBatch(MultipartFile file) {
        List<CsvInvoiceBatchParser.ParsedRow> parsedRows = csvParser.parse(file);

        List<BatchRowResult> results = new ArrayList<>();
        int certified = 0;

        for (CsvInvoiceBatchParser.ParsedRow row : parsedRows) {
            BatchRowResult result;

            if (row.request() != null) {
                VerificationResponse response = verifyAndProcess(row.request());
                result = new BatchRowResult(row.rowNumber(), response.invoiceNumber(), response.status(),
                    response.errorCount(), response.errors(), response.lhdnUuid());
            } else {
                // Field-level parse defect: record the rejection when we at least know the invoice number.
                persistRawRejection(row);
                result = new BatchRowResult(row.rowNumber(), row.invoiceNumber(), STATUS_REJECTED,
                    row.parseErrors().size(), row.parseErrors(), null);
            }

            if (result.certified()) {
                certified++;
            }
            results.add(result);
        }

        return new BatchUploadResponse(file.getOriginalFilename(), results.size(), certified,
            results.size() - certified, results);
    }

    public List<InvoiceAuditRecord> getAuditRecords(String status) {
        if (status == null || status.isBlank()) {
            return repository.findAllByOrderByIdDesc();
        }
        return repository.findByStatusOrderByIdDesc(status.trim().toUpperCase(Locale.ROOT));
    }

    private VerificationResponse reject(InvoiceRequest invoice, List<String> errors) {
        persist(invoice, STATUS_REJECTED, String.join(" | ", errors), null, null);
        return VerificationResponse.rejected(invoice.invoiceNumber(), errors);
    }

    /**
     * Upserts the audit record for an invoice number. On a repeat submission every mutable field is
     * refreshed so the stored manifest always describes the most recent attempt.
     */
    private void persist(InvoiceRequest invoice, String status, String errorManifest, String uuid, String qrUrl) {
        String invoiceNumber = invoice.invoiceNumber() == null ? null : invoice.invoiceNumber().trim();
        if (invoiceNumber == null || invoiceNumber.isEmpty()) {
            // Nothing to key the audit trail on; the verdict is still returned to the caller.
            return;
        }

        repository.findByInvoiceNumber(invoiceNumber).ifPresentOrElse(
            existing -> {
                existing.setBuyerName(invoice.buyerName());
                existing.setBuyerIdValue(invoice.buyerIdValue());
                existing.setBuyerTin(invoice.buyerTin());
                existing.applyVerification(invoice.totalPayable(), status, errorManifest, uuid, qrUrl);
                repository.save(existing);
            },
            () -> repository.save(new InvoiceAuditRecord(
                invoiceNumber,
                invoice.buyerName(),
                invoice.buyerIdValue(),
                invoice.buyerTin(),
                invoice.totalPayable(),
                status,
                errorManifest,
                uuid,
                qrUrl
            ))
        );
    }

    private void persistRawRejection(CsvInvoiceBatchParser.ParsedRow row) {
        String invoiceNumber = row.invoiceNumber() == null ? null : row.invoiceNumber().trim();
        if (invoiceNumber == null || invoiceNumber.isEmpty()) {
            return;
        }
        String manifest = String.join(" | ", row.parseErrors());

        repository.findByInvoiceNumber(invoiceNumber).ifPresentOrElse(
            existing -> {
                existing.setBuyerName(row.buyerName());
                existing.setBuyerIdValue(row.buyerIdValue());
                existing.setBuyerTin(row.buyerTin());
                existing.applyVerification(row.totalPayable(), STATUS_REJECTED, manifest, null, null);
                repository.save(existing);
            },
            () -> repository.save(new InvoiceAuditRecord(
                invoiceNumber, row.buyerName(), row.buyerIdValue(), row.buyerTin(),
                row.totalPayable(), STATUS_REJECTED, manifest, null, null))
        );
    }

    private static boolean itemsComplete(List<LineItemRequest> items) {
        return items.stream().allMatch(item -> item != null
            && item.quantity() != null && item.unitPrice() != null
            && item.sstRate() != null && item.lineTotal() != null);
    }

    private static boolean headerComplete(InvoiceRequest invoice) {
        return invoice.subtotal() != null && invoice.totalTax() != null && invoice.totalPayable() != null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
