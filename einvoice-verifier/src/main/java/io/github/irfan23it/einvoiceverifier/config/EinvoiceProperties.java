package io.github.irfan23it.einvoiceverifier.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Externalised business rules for the pre-flight gatekeeper.
 *
 * <p>Every value defaults to the LHDN MyInvois baseline, so the service still behaves correctly
 * when a property is omitted. Overriding these in {@code application.properties} (or any
 * environment-specific profile) adjusts the rules without a code change — the point being that
 * production-specific definitions of a TIN, a BRN or an approved SST rate can be tuned on site.
 */
@ConfigurationProperties(prefix = "einvoice")
public record EinvoiceProperties(Rules rules, Gateway gateway) {

    public EinvoiceProperties {
        if (rules == null) {
            rules = new Rules(null, null, null, 0);
        }
        if (gateway == null) {
            gateway = new Gateway(null, null);
        }
    }

    /**
     * @param tinPattern      regex a buyer TIN must match.
     * @param buyerIdPattern  regex a BRN / NRIC value must match.
     * @param allowedSstRates approved service tax rates, e.g. {@code 0.00, 0.06, 0.08}.
     * @param maxBatchRows    maximum data rows accepted in one CSV upload.
     */
    public record Rules(
        String tinPattern,
        String buyerIdPattern,
        List<BigDecimal> allowedSstRates,
        int maxBatchRows
    ) {
        private static final String DEFAULT_ID_PATTERN = "^[0-9]{12}$";

        public Rules {
            if (tinPattern == null || tinPattern.isBlank()) {
                tinPattern = DEFAULT_ID_PATTERN;
            }
            if (buyerIdPattern == null || buyerIdPattern.isBlank()) {
                buyerIdPattern = DEFAULT_ID_PATTERN;
            }
            if (allowedSstRates == null || allowedSstRates.isEmpty()) {
                allowedSstRates = List.of(
                    new BigDecimal("0.00"), new BigDecimal("0.06"), new BigDecimal("0.08"));
            }
            if (maxBatchRows <= 0) {
                maxBatchRows = 5000;
            }
        }
    }

    /**
     * @param uuidPrefix synthetic LHDN clearance identifier prefix.
     * @param qrBaseUrl  base URL the clearance identifier is appended to for QR verification.
     */
    public record Gateway(String uuidPrefix, String qrBaseUrl) {
        public Gateway {
            if (uuidPrefix == null || uuidPrefix.isBlank()) {
                uuidPrefix = "LHDN-MYINVOIS-";
            }
            if (qrBaseUrl == null || qrBaseUrl.isBlank()) {
                qrBaseUrl = "https://myinvois.hasil.gov.my/taxpayer/validate/";
            }
        }
    }
}
