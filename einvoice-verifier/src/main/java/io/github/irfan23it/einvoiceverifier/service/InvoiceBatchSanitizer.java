package io.github.irfan23it.einvoiceverifier.service;

import io.github.irfan23it.einvoiceverifier.config.EinvoiceProperties;
import io.github.irfan23it.einvoiceverifier.dto.BatchRowResult;
import io.github.irfan23it.einvoiceverifier.dto.BatchUploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates CSV batch sanitization.
 *
 * <p>Deliberately <strong>not</strong> transactional at file level. Each row is delegated to
 * {@link InvoiceVerificationService#processRow}, which opens its own {@code REQUIRES_NEW}
 * transaction. A row that fails unexpectedly is therefore rolled back on its own: rows committed
 * before it stay committed, and the remaining rows are still processed. Reporting the failure in
 * place — rather than aborting the file — is what makes this a sanitiser rather than an all-or-nothing
 * importer.
 */
@Service
public class InvoiceBatchSanitizer {

    private static final Logger log = LoggerFactory.getLogger(InvoiceBatchSanitizer.class);

    private final CsvInvoiceBatchParser csvParser;
    private final InvoiceVerificationService verificationService;
    private final int maxBatchRows;

    public InvoiceBatchSanitizer(CsvInvoiceBatchParser csvParser,
                                 InvoiceVerificationService verificationService,
                                 EinvoiceProperties properties) {
        this.csvParser = csvParser;
        this.verificationService = verificationService;
        this.maxBatchRows = properties.rules().maxBatchRows();
    }

    public BatchUploadResponse processBatch(MultipartFile file) {
        List<CsvInvoiceBatchParser.ParsedRow> parsedRows = csvParser.parse(file);

        if (parsedRows.size() > maxBatchRows) {
            throw new IllegalArgumentException("Batch contains " + parsedRows.size()
                + " row(s), which exceeds the configured limit of " + maxBatchRows + ".");
        }

        List<BatchRowResult> results = new ArrayList<>(parsedRows.size());
        int certified = 0;

        for (CsvInvoiceBatchParser.ParsedRow row : parsedRows) {
            BatchRowResult result;
            try {
                result = verificationService.processRow(row);
            } catch (RuntimeException ex) {
                // The row's own transaction has already rolled back. Keep the batch moving so a
                // single corrupt row cannot cost the operator the rest of the file.
                log.error("Row {} (invoice '{}') failed unexpectedly and was rolled back; "
                        + "previously committed rows are unaffected.",
                    row.rowNumber(), row.invoiceNumber(), ex);
                result = new BatchRowResult(row.rowNumber(), row.invoiceNumber(), "REJECTED_INTERNAL",
                    1, List.of("System error while processing this row; the row was not persisted."), null);
            }

            if (result.certified()) {
                certified++;
            }
            results.add(result);
        }

        return new BatchUploadResponse(file.getOriginalFilename(), results.size(), certified,
            results.size() - certified, results);
    }
}
