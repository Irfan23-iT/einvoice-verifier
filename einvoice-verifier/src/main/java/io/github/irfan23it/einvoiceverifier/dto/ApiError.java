package io.github.irfan23it.einvoiceverifier.dto;

/** Minimal error envelope for failures that are not invoice verdicts (e.g. batch upload problems). */
public record ApiError(String status, String message) {}
