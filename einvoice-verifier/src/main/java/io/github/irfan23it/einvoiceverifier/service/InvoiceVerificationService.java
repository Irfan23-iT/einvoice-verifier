package io.github.irfan23it.einvoiceverifier.service;

import io.github.irfan23it.einvoiceverifier.config.EinvoiceProperties;
import io.github.irfan23it.einvoiceverifier.dto.BatchRowResult;
import io.github.irfan23it.einvoiceverifier.dto.InvoiceRequest;
import io.github.irfan23it.einvoiceverifier.dto.LineItemRequest;
import io.github.irfan23it.einvoiceverifier.dto.VerificationResponse;
import io.github.irfan23it.einvoiceverifier.entity.InvoiceAuditRecord;
import io.github.irfan23it.einvoiceverifier.repository.InvoiceAuditRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class InvoiceVerificationService {

    private static final String STATUS_REJECTED = "REJECTED_INTERNAL";
    private static final String STATUS_CERTIFIED = "CERTIFIED_LHDN";

    private final InvoiceAuditRepository repository;
    private final Validator validator;

    private final Pattern tinPattern;
    private final String tinPatternText;
    private final Pattern buyerIdPattern;
    private final String buyerIdPatternText;
    private final List<BigDecimal> allowedSstRates;
    private final String allowedSstRatesText;
    private final String uuidPrefix;
    private final String qrBaseUrl;

    public InvoiceVerificationService(InvoiceAuditRepository repository,
                                      Validator validator,
                                      EinvoiceProperties properties) {
        this.repository = repository;
        this.validator = validator;

        EinvoiceProperties.Rules rules = properties.rules();
        this.tinPattern = Pattern.compile(rules.tinPattern());
        this.tinPatternText = rules.tinPattern();
        this.buyerIdPattern = Pattern.compile(rules.buyerIdPattern());
        this.buyerIdPatternText = rules.buyerIdPattern();
        this.allowedSstRates = List.copyOf(rules.allowedSstRates());
        this.allowedSstRatesText = this.allowedSstRates.stream()
            .map(BigDecimal::toPlainString)
            .collect(Collectors.joining(", "));

        EinvoiceProperties.Gateway gateway = properties.gateway();
        this.uuidPrefix = gateway.uuidPrefix();
        this.qrBaseUrl = gateway.qrBaseUrl();
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
        if (notBlank(invoice.buyerTin()) && !tinPattern.matcher(invoice.buyerTin().trim()).matches()) {
            errors.add("Format Error: buyerTin does not match the required Malaysian TIN format ("
                + tinPatternText + "), received: '" + invoice.buyerTin() + "'");
        }

        if (notBlank(invoice.buyerIdType())) {
            if ("BRN".equalsIgnoreCase(invoice.buyerIdType()) || "NRIC".equalsIgnoreCase(invoice.buyerIdType())) {
                if (invoice.buyerIdValue() == null
                        || !buyerIdPattern.matcher(invoice.buyerIdValue().trim()).matches()) {
                    errors.add("Format Error: " + invoice.buyerIdType()
                        + " does not match the required identity format (" + buyerIdPatternText
                        + "), received: '" + invoice.buyerIdValue() + "'");
                }
            } else {
                errors.add("Invalid buyerIdType: Must be either 'BRN' or 'NRIC'.");
            }
        }

        errors.addAll(checkServiceTaxRates(invoice.items()));

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
        String lhdnUuid = uuidPrefix + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        String qrCodeUrl = qrBaseUrl + lhdnUuid;

        persist(invoice, STATUS_CERTIFIED, "NONE", lhdnUuid, qrCodeUrl);
        return VerificationResponse.certified(invoice.invoiceNumber(), lhdnUuid, qrCodeUrl);
    }

    /**
     * Processes one parsed CSV row inside its own transaction.
     *
     * <p>{@code REQUIRES_NEW} is what gives the batch per-row isolation: the row commits (or rolls
     * back) independently of every other row, so one unexpected failure can never discard verdicts
     * that were already written.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchRowResult processRow(CsvInvoiceBatchParser.ParsedRow row) {
        if (row.request() != null) {
            VerificationResponse response = verifyAndProcess(row.request());
            return new BatchRowResult(row.rowNumber(), response.invoiceNumber(), response.status(),
                response.errorCount(), response.errors(), response.lhdnUuid());
        }

        // Field-level parse defect: record the rejection when we at least know the invoice number.
        persistRawRejection(row);
        return new BatchRowResult(row.rowNumber(), row.invoiceNumber(), STATUS_REJECTED,
            row.parseErrors().size(), row.parseErrors(), null);
    }

    public List<InvoiceAuditRecord> getAuditRecords(String status) {
        if (status == null || status.isBlank()) {
            return repository.findAllByOrderByIdDesc();
        }
        return repository.findByStatusOrderByIdDesc(status.trim().toUpperCase(Locale.ROOT));
    }

    /** Rejects any line whose service tax rate is not on the approved schedule. */
    private List<String> checkServiceTaxRates(List<LineItemRequest> items) {
        if (items == null) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            LineItemRequest item = items.get(i);
            if (item == null || item.sstRate() == null) {
                continue;
            }
            boolean approved = allowedSstRates.stream().anyMatch(rate -> rate.compareTo(item.sstRate()) == 0);
            if (!approved) {
                violations.add("Rule Violation at Item " + (i + 1) + " (" + item.description() + "): sstRate "
                    + item.sstRate() + " is not an approved Malaysian SST rate (allowed: " + allowedSstRatesText + ")");
            }
        }
        return violations;
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
