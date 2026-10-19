package io.github.irfan23it.einvoiceverifier.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "invoice_audit_records")
public class InvoiceAuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String invoiceNumber;

    private String buyerName;
    private String buyerIdValue;
    private String buyerTin;

    private BigDecimal totalPayable;

    private String status; // "REJECTED_INTERNAL", "READY_FOR_LHDN", "CERTIFIED_LHDN"

    @Column(columnDefinition = "TEXT")
    private String errorManifest; // Stores any calculation/format errors

    private String lhdnUuid;      // Official UUID assigned if passed
    private String qrCodeUrl;     // Verification link

    private Instant createdAt;       // First time this invoice number was seen
    private Instant lastVerifiedAt;  // Most recent verification attempt
    private int attemptCount;        // Number of submissions for this invoice number

    public InvoiceAuditRecord() {}

    public InvoiceAuditRecord(String invoiceNumber, String buyerName, String buyerIdValue,
                              String buyerTin, BigDecimal totalPayable, String status,
                              String errorManifest, String lhdnUuid, String qrCodeUrl) {
        this.invoiceNumber = invoiceNumber;
        this.buyerName = buyerName;
        this.buyerIdValue = buyerIdValue;
        this.buyerTin = buyerTin;
        this.totalPayable = totalPayable;
        this.status = status;
        this.errorManifest = errorManifest;
        this.lhdnUuid = lhdnUuid;
        this.qrCodeUrl = qrCodeUrl;
        this.createdAt = Instant.now();
        this.lastVerifiedAt = this.createdAt;
        this.attemptCount = 1;
    }

    /**
     * Refreshes the outcome fields after a repeat submission of the same invoice number.
     * Every mutable field is overwritten so the persisted audit trail always reflects the
     * {@code latest} attempt rather than a mixture of old and new values.
     */
    public void applyVerification(BigDecimal totalPayable, String status, String errorManifest,
                                  String lhdnUuid, String qrCodeUrl) {
        this.totalPayable = totalPayable;
        this.status = status;
        this.errorManifest = errorManifest;
        this.lhdnUuid = lhdnUuid;
        this.qrCodeUrl = qrCodeUrl;
        this.lastVerifiedAt = Instant.now();
        this.attemptCount++;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }
    public String getBuyerIdValue() { return buyerIdValue; }
    public void setBuyerIdValue(String buyerIdValue) { this.buyerIdValue = buyerIdValue; }
    public String getBuyerTin() { return buyerTin; }
    public void setBuyerTin(String buyerTin) { this.buyerTin = buyerTin; }
    public BigDecimal getTotalPayable() { return totalPayable; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorManifest() { return errorManifest; }
    public void setErrorManifest(String errorManifest) { this.errorManifest = errorManifest; }
    public String getLhdnUuid() { return lhdnUuid; }
    public void setLhdnUuid(String lhdnUuid) { this.lhdnUuid = lhdnUuid; }
    public String getQrCodeUrl() { return qrCodeUrl; }
    public void setQrCodeUrl(String qrCodeUrl) { this.qrCodeUrl = qrCodeUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public int getAttemptCount() { return attemptCount; }
}
