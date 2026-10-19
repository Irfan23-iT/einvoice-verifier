package io.github.irfan23it.einvoiceverifier.dto;

import java.util.List;

/**
 * Aggregate result of a batch CSV sanitization run.
 *
 * @param fileName   Original name of the uploaded file.
 * @param totalRows  Number of data rows parsed.
 * @param certified  Rows that cleared every rule.
 * @param rejected   Rows intercepted before gateway submission.
 * @param rows       Per-row verdicts, in file order.
 */
public record BatchUploadResponse(
    String fileName,
    int totalRows,
    int certified,
    int rejected,
    List<BatchRowResult> rows
) {}
