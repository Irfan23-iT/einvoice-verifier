package io.github.irfan23it.einvoiceverifier.dto;

import java.util.List;

/**
 * Verdict returned by the pre-flight gatekeeper.
 *
 * @param status   {@code "CERTIFIED_LHDN"} when the payload cleared every rule,
 *                 otherwise {@code "REJECTED_INTERNAL"}.
 * @param errors    Human-readable rejection reasons; empty when certified.
 * @param lhdnUuid  Mock LHDN MyInvois clearance identifier; {@code null} when rejected.
 * @param qrCodeUrl Verification URL issued alongside the certificate; {@code null} when rejected.
 */
public record VerificationResponse(
    String invoiceNumber,
    String status,           // "CERTIFIED_LHDN" or "REJECTED_INTERNAL"
    int errorCount,
    List<String> errors,
    String lhdnUuid,
    String qrCodeUrl
) {
    public static VerificationResponse certified(String invoiceNumber, String lhdnUuid, String qrCodeUrl) {
        return new VerificationResponse(invoiceNumber, "CERTIFIED_LHDN", 0, List.of(), lhdnUuid, qrCodeUrl);
    }

    public static VerificationResponse rejected(String invoiceNumber, List<String> errors) {
        return new VerificationResponse(invoiceNumber, "REJECTED_INTERNAL", errors.size(), List.copyOf(errors), null, null);
    }
}
