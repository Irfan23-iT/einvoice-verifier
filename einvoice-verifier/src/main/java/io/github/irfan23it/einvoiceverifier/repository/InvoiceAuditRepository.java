package io.github.irfan23it.einvoiceverifier.repository;

import io.github.irfan23it.einvoiceverifier.entity.InvoiceAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvoiceAuditRepository extends JpaRepository<InvoiceAuditRecord, Long> {

    Optional<InvoiceAuditRecord> findByInvoiceNumber(String invoiceNumber);

    List<InvoiceAuditRecord> findByStatusOrderByIdDesc(String status);

    List<InvoiceAuditRecord> findAllByOrderByIdDesc();
}
