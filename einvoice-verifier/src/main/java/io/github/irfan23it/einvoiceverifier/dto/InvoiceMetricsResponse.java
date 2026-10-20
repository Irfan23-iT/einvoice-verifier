package io.github.irfan23it.einvoiceverifier.dto;

import java.math.BigDecimal;

/**
 * Executive KPI snapshot of the audit ledger.
 *
 * <p>Aggregates are computed over the persisted records, so the figures describe the latest verdict
 * per invoice number rather than individual HTTP calls.
 *
 * @param totalInvoices        unique invoices in the audit trail
 * @param certifiedCount       invoices currently holding a clearance identifier
 * @param rejectedCount        invoices currently intercepted
 * @param clearanceRatePercent certified / total, rounded to one decimal place
 * @param senDriftDefectCount  rejected invoices whose manifest records an arithmetic mismatch
 * @param totalGrossAudited    sum of every stored total payable, at sen precision
 */
public record InvoiceMetricsResponse(
    long totalInvoices,
    long certifiedCount,
    long rejectedCount,
    BigDecimal clearanceRatePercent,
    long senDriftDefectCount,
    BigDecimal totalGrossAudited
) {
}
