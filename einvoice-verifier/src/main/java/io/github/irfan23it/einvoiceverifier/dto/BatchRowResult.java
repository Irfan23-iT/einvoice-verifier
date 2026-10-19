package io.github.irfan23it.einvoiceverifier.dto;

import java.util.List;

/**
 * Outcome of sanitizing a single row of an uploaded billing CSV.
 *
 * @param row         1-based physical line number in the uploaded file (header excluded).
 * @param status      {@code "CERTIFIED_LHDN"} or {@code "REJECTED_INTERNAL"}.
 * @param errorCount  Number of defects detected on the row.
 * @param errors      Human-readable defect manifest.
 * @param lhdnUuid    Mock clearance identifier when certified; {@code null} otherwise.
 */
public record BatchRowResult(
    int row,
    String invoiceNumber,
    String status,
    int errorCount,
    List<String> errors,
    String lhdnUuid
) {
    public boolean certified() {
        return "CERTIFIED_LHDN".equals(status);
    }
}
