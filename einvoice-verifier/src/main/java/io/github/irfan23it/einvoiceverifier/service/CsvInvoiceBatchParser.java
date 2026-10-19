package io.github.irfan23it.einvoiceverifier.service;

import io.github.irfan23it.einvoiceverifier.dto.InvoiceRequest;
import io.github.irfan23it.einvoiceverifier.dto.LineItemRequest;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses billing CSV dumps into verifiable {@link InvoiceRequest} objects.
 *
 * <p>Parsing is deliberately tolerant: a defect in a single cell never aborts the run.
 * Instead the offending value is reported in {@link ParsedRow#parseErrors()} so the rest
 * of the batch can still be sanitized. Only a structurally unusable <em>file</em>
 * (missing header columns, unreadable stream) raises an exception.
 *
 * <p>Expected header (exactly these column names, any order):
 * <pre>
 * invoiceNumber,buyerName,buyerIdType,buyerIdValue,buyerTin,desc,qty,price,sstRate,lineTotal,subtotal,totalTax,totalPayable
 * </pre>
 */
@Component
public class CsvInvoiceBatchParser {

    private static final List<String> REQUIRED_HEADERS = List.of(
        "invoiceNumber", "buyerName", "buyerIdType", "buyerIdValue", "buyerTin",
        "desc", "qty", "price", "sstRate", "lineTotal",
        "subtotal", "totalTax", "totalPayable"
    );

    /**
     * @param rowNumber    1-based index of the data row within the file (header excluded).
     * @param request      Fully materialised payload, or {@code null} when any cell failed to parse.
     * @param parseErrors  Cell-level defects; empty when {@code request} is non-null.
     */
    public record ParsedRow(
        int rowNumber,
        String invoiceNumber,
        String buyerName,
        String buyerIdValue,
        String buyerTin,
        BigDecimal totalPayable,
        InvoiceRequest request,
        List<String> parseErrors
    ) {}

    public List<ParsedRow> parse(MultipartFile file) {
        try (
            Reader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build()
                .parse(reader)
        ) {
            requireHeaders(parser.getHeaderMap().keySet());

            List<ParsedRow> rows = new ArrayList<>();
            int rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                rows.add(toRow(record, rowNumber));
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read the uploaded CSV file: " + e.getMessage(), e);
        }
    }

    private void requireHeaders(Set<String> present) {
        List<String> missing = REQUIRED_HEADERS.stream().filter(h -> !present.contains(h)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "CSV is missing required column(s): " + String.join(", ", missing)
                    + ". Expected header: " + String.join(",", REQUIRED_HEADERS));
        }
    }

    private ParsedRow toRow(CSVRecord record, int rowNumber) {
        List<String> errors = new ArrayList<>();

        String invoiceNumber = value(record, "invoiceNumber");
        String buyerName = value(record, "buyerName");
        String buyerIdType = value(record, "buyerIdType");
        String buyerIdValue = value(record, "buyerIdValue");
        String buyerTin = value(record, "buyerTin");
        String desc = value(record, "desc");

        BigDecimal qty = number(record, "qty", rowNumber, errors);
        BigDecimal price = number(record, "price", rowNumber, errors);
        BigDecimal sstRate = number(record, "sstRate", rowNumber, errors);
        BigDecimal lineTotal = number(record, "lineTotal", rowNumber, errors);
        BigDecimal subtotal = number(record, "subtotal", rowNumber, errors);
        BigDecimal totalTax = number(record, "totalTax", rowNumber, errors);
        BigDecimal totalPayable = number(record, "totalPayable", rowNumber, errors);

        InvoiceRequest request = null;
        if (errors.isEmpty()) {
            request = new InvoiceRequest(
                invoiceNumber, buyerName, buyerIdType, buyerIdValue, buyerTin,
                List.of(new LineItemRequest(desc, qty, price, sstRate, lineTotal)),
                subtotal, totalTax, totalPayable);
        }

        return new ParsedRow(rowNumber, invoiceNumber, buyerName, buyerIdValue, buyerTin,
            totalPayable, request, errors);
    }

    private String value(CSVRecord record, String column) {
        if (!record.isMapped(column) || !record.isSet(column)) {
            return null;
        }
        String raw = record.get(column);
        return raw == null || raw.isBlank() ? null : raw;
    }

    private BigDecimal number(CSVRecord record, String column, int rowNumber, List<String> errors) {
        String raw = value(record, column);
        if (raw == null) {
            errors.add("Row " + rowNumber + ": column '" + column + "' is empty.");
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            errors.add("Row " + rowNumber + ": column '" + column + "' is not a valid number ('" + raw + "').");
            return null;
        }
    }
}
