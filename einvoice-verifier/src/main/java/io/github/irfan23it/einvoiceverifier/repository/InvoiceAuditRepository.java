package io.github.irfan23it.einvoiceverifier.repository;

import io.github.irfan23it.einvoiceverifier.entity.InvoiceAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface InvoiceAuditRepository extends JpaRepository<InvoiceAuditRecord, Long> {

    Optional<InvoiceAuditRecord> findByInvoiceNumber(String invoiceNumber);

    List<InvoiceAuditRecord> findByStatusOrderByIdDesc(String status);

    List<InvoiceAuditRecord> findAllByOrderByIdDesc();

    long countByStatus(String status);

    /**
     * Counts rejections whose stored manifest records an arithmetic defect.
     *
     * <p>The service writes those defects as {@code Math Mismatch}, {@code Subtotal Mismatch},
     * {@code Total Tax Mismatch} and {@code Total Payable Mismatch}, so {@code Mismatch} is the
     * reliable marker. A search for the words "drift" or "arithmetic" would never match a real
     * manifest and would silently report zero.
     */
    @Query("SELECT COUNT(a) FROM InvoiceAuditRecord a WHERE a.errorManifest LIKE '%Mismatch%'")
    long countSenDriftDefects();

    /**
     * Sum of every stored total payable. Returns {@code null} on an empty ledger, which the service
     * normalises to zero — a JPQL {@code COALESCE(..., 0)} would risk binding an integer literal
     * against a {@link BigDecimal} column.
     */
    @Query("SELECT SUM(a.totalPayable) FROM InvoiceAuditRecord a")
    BigDecimal sumTotalPayable();
}
