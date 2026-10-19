package io.github.irfan23it.einvoiceverifier.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record LineItemRequest(

    @NotBlank(message = "description is mandatory")
    String description,

    @NotNull(message = "quantity is mandatory")
    @DecimalMin(value = "0.0", inclusive = false, message = "quantity must be greater than zero")
    BigDecimal quantity,

    @NotNull(message = "unitPrice is mandatory")
    @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
    BigDecimal unitPrice,

    @NotNull(message = "sstRate is mandatory")
    @DecimalMin(value = "0.0", message = "sstRate must not be negative")
    @DecimalMax(value = "1.0", message = "sstRate must not exceed 1.0 (100%)")
    BigDecimal sstRate,      // e.g. 0.08 for 8% SST

    @NotNull(message = "lineTotal is mandatory")
    @DecimalMin(value = "0.0", message = "lineTotal must not be negative")
    BigDecimal lineTotal
) {}
