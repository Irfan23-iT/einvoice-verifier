package io.github.irfan23it.einvoiceverifier.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound e-Invoice payload submitted for pre-flight verification.
 *
 * <p>Structural constraints (presence, non-negativity) are enforced declaratively via
 * Jakarta Bean Validation. Business rules that require cross-field or sen-level evaluation
 * (buyer identity format, line/total arithmetic) are enforced by
 * {@code InvoiceVerificationService}.
 */
public record InvoiceRequest(

    @NotBlank(message = "invoiceNumber is mandatory")
    @Size(max = 100, message = "invoiceNumber must not exceed 100 characters")
    String invoiceNumber,

    @NotBlank(message = "buyerName is mandatory")
    String buyerName,

    @NotBlank(message = "buyerIdType is mandatory")
    String buyerIdType,    // "BRN" or "NRIC"

    @NotBlank(message = "buyerIdValue is mandatory")
    String buyerIdValue,   // e.g. "202401009876" (12 digits)

    @NotBlank(message = "buyerTin is mandatory")
    String buyerTin,       // e.g. "C2938475610"

    @NotEmpty(message = "Invoice must contain at least one line item")
    List<@Valid LineItemRequest> items,

    @NotNull(message = "subtotal is mandatory")
    @DecimalMin(value = "0.0", message = "subtotal must not be negative")
    BigDecimal subtotal,

    @NotNull(message = "totalTax is mandatory")
    @DecimalMin(value = "0.0", message = "totalTax must not be negative")
    BigDecimal totalTax,

    @NotNull(message = "totalPayable is mandatory")
    @DecimalMin(value = "0.0", message = "totalPayable must not be negative")
    BigDecimal totalPayable
) {}
