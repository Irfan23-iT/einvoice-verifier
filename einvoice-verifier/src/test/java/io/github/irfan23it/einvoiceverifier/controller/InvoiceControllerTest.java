package io.github.irfan23it.einvoiceverifier.controller;

import io.github.irfan23it.einvoiceverifier.entity.InvoiceAuditRecord;
import io.github.irfan23it.einvoiceverifier.repository.InvoiceAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP tests: real Spring context, real H2, real multipart parsing.
 *
 * <p>These cover the layer the service tests cannot: request binding, the error contract owned by
 * {@link GlobalExceptionHandler}, status codes and the shape the dashboard consumes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceControllerTest {

    private static final String VALID_PAYLOAD = """
        {
          "invoiceNumber": "WEB-001",
          "buyerName": "Maju Holdings Sdn Bhd",
          "buyerIdType": "BRN",
          "buyerIdValue": "202401009876",
          "buyerTin": "293847561012",
          "items": [
            { "description": "Trade Facility Fee", "quantity": 1, "unitPrice": 2000.00,
              "sstRate": 0.08, "lineTotal": 2160.00 }
          ],
          "subtotal": 2000.00,
          "totalTax": 160.00,
          "totalPayable": 2160.00
        }
        """;

    private static final String CSV_HEADER =
        "invoiceNumber,buyerName,buyerIdType,buyerIdValue,buyerTin,desc,qty,price,sstRate,lineTotal,subtotal,totalTax,totalPayable";

    private static final String CLEAN_CSV_ROW =
        "WEB-B-001,Clean Sdn Bhd,BRN,202401001111,102938475010,Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.00";

    private static final String DEFECTIVE_CSV_ROW =
        "WEB-B-002,Legacy Sdn Bhd,BRN,1234567890,998877665544,Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InvoiceAuditRepository repository;

    @BeforeEach
    void clearAuditTrail() {
        repository.deleteAll();
    }

    // ------------------------------------------------------------ POST /verify

    @Test
    @DisplayName("A certified payload returns 200 with a clearance identifier and QR URL")
    void verifyCertifiedPayload() throws Exception {
        mockMvc.perform(post("/api/einvoice/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PAYLOAD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.invoiceNumber").value("WEB-001"))
            .andExpect(jsonPath("$.status").value("CERTIFIED_LHDN"))
            .andExpect(jsonPath("$.errorCount").value(0))
            .andExpect(jsonPath("$.lhdnUuid").isNotEmpty())
            .andExpect(jsonPath("$.qrCodeUrl").isNotEmpty());

        List<InvoiceAuditRecord> stored = repository.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getStatus()).isEqualTo("CERTIFIED_LHDN");
        assertThat(stored.get(0).getLhdnUuid()).startsWith("LHDN-MYINVOIS-");
        assertThat(stored.get(0).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A rejected payload still returns 200, carrying the defect manifest")
    void verifyRejectedPayload() throws Exception {
        String payload = VALID_PAYLOAD
            .replace("WEB-001", "WEB-002")
            .replace("293847561012", "X2938475610");

        MvcResult result = mockMvc.perform(post("/api/einvoice/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED_INTERNAL"))
            .andExpect(jsonPath("$.errorCount").value(1))
            .andExpect(jsonPath("$.lhdnUuid").doesNotExist())
            .andReturn();

        assertThat(body(result)).contains("Malaysian TIN format");
        assertThat(repository.findAll().get(0).getStatus()).isEqualTo("REJECTED_INTERNAL");
    }

    @Test
    @DisplayName("Malformed JSON returns 400 in the invoice-verdict envelope, not a raw 500")
    void malformedJsonReturnsBadRequest() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/einvoice/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invoiceNumber\": "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("REJECTED_INTERNAL"))
            .andExpect(jsonPath("$.errorCount").value(1))
            .andReturn();

        assertThat(body(result)).contains("Malformed JSON payload");
    }

    @Test
    @DisplayName("A wrongly-typed field is treated as malformed JSON")
    void wrongFieldTypeReturnsBadRequest() throws Exception {
        String payload = VALID_PAYLOAD.replace("\"quantity\": 1", "\"quantity\": \"many\"");

        mockMvc.perform(post("/api/einvoice/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("REJECTED_INTERNAL"));
    }

    @Test
    @DisplayName("An empty body returns 400 rather than an empty 200")
    void emptyBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/einvoice/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("REJECTED_INTERNAL"));
    }

    // ------------------------------------------------------ POST /upload-batch

    @Test
    @DisplayName("A batch upload returns per-row verdicts and aggregate counts")
    void uploadBatchSplitsRows() throws Exception {
        mockMvc.perform(multipart("/api/einvoice/upload-batch").file(csv(CLEAN_CSV_ROW, DEFECTIVE_CSV_ROW)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileName").value("billing-batch.csv"))
            .andExpect(jsonPath("$.totalRows").value(2))
            .andExpect(jsonPath("$.certified").value(1))
            .andExpect(jsonPath("$.rejected").value(1))
            .andExpect(jsonPath("$.rows[0].invoiceNumber").value("WEB-B-001"))
            .andExpect(jsonPath("$.rows[0].status").value("CERTIFIED_LHDN"))
            .andExpect(jsonPath("$.rows[1].status").value("REJECTED_INTERNAL"));

        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("A non-CSV upload is rejected with the documented error envelope")
    void nonCsvUploadIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "billing.txt", "text/plain",
            CLEAN_CSV_ROW.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/einvoice/upload-batch").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("REQUEST_REJECTED"))
            .andReturn();

        assertThat(body(result)).contains(".csv");
    }

    @Test
    @DisplayName("An empty upload is rejected as a missing file")
    void emptyUploadIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "billing.csv", "text/csv", new byte[0]);

        MvcResult result = mockMvc.perform(multipart("/api/einvoice/upload-batch").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("REQUEST_REJECTED"))
            .andReturn();

        assertThat(body(result)).contains("No CSV file was uploaded");
    }

    @Test
    @DisplayName("A request with no file part keeps the documented error envelope")
    void missingFilePartIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/einvoice/upload-batch"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("REQUEST_REJECTED"))
            .andReturn();

        assertThat(body(result)).contains("No CSV file was uploaded");
    }

    @Test
    @DisplayName("A CSV missing required columns is rejected with the parser's reason")
    void malformedCsvHeaderIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "billing.csv", "text/csv",
            "invoiceNumber,buyerName\nWEB-X,Acme\n".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/einvoice/upload-batch").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("REQUEST_REJECTED"))
            .andReturn();

        assertThat(body(result)).contains("missing required column");
    }

    @Test
    @DisplayName("An unexpected row failure rolls back only that row; earlier rows stay committed")
    void batchRowFailureIsIsolated() throws Exception {
        // Overruns the audit column's length, so persistence fails at commit time for this row only.
        String oversizedInvoiceNumber = "X".repeat(300);

        mockMvc.perform(multipart("/api/einvoice/upload-batch").file(csv(
                CLEAN_CSV_ROW,
                oversizedInvoiceNumber
                    + ",Broken Sdn Bhd,BRN,202401001111,102938475010,Fee,1,1000.00,0.08,1080.00,1000.00,80.00,1080.00")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalRows").value(2))
            .andExpect(jsonPath("$.certified").value(1))
            .andExpect(jsonPath("$.rejected").value(1))
            .andExpect(jsonPath("$.rows[0].status").value("CERTIFIED_LHDN"))
            .andExpect(jsonPath("$.rows[1].status").value("REJECTED_INTERNAL"))
            .andExpect(jsonPath("$.rows[1].errors[0]").value("System error while processing this row; the row was not persisted."));

        // The clean row was committed in its own transaction and survives the later failure.
        assertThat(repository.findAll())
            .extracting(InvoiceAuditRecord::getInvoiceNumber)
            .containsExactly("WEB-B-001");
    }

    // -------------------------------------------------------- GET /history

    @Test
    @DisplayName("History lists the newest attempt first")
    void historyIsNewestFirst() throws Exception {
        submit(VALID_PAYLOAD);

        String rejected = VALID_PAYLOAD
            .replace("WEB-001", "WEB-003")
            .replace("2160.00", "2160.01");
        submit(rejected);

        mockMvc.perform(get("/api/einvoice/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].invoiceNumber").value("WEB-003"))
            .andExpect(jsonPath("$[1].invoiceNumber").value("WEB-001"));
    }

    @Test
    @DisplayName("History honours the status filter")
    void historyFilterByStatus() throws Exception {
        submit(VALID_PAYLOAD);
        submit(VALID_PAYLOAD.replace("WEB-001", "WEB-004").replace("2160.00", "2160.01"));

        mockMvc.perform(get("/api/einvoice/history").param("status", "CERTIFIED_LHDN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].invoiceNumber").value("WEB-001"));
    }

    @Test
    @DisplayName("An unknown status filter returns an empty list, not an error")
    void historyUnknownStatusReturnsEmptyList() throws Exception {
        submit(VALID_PAYLOAD);

        mockMvc.perform(get("/api/einvoice/history").param("status", "NOT_A_STATUS"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // --------------------------------------------------------- GET /metrics

    @Test
    @DisplayName("Metrics are all zero on an empty ledger")
    void metricsAreZeroOnAnEmptyLedger() throws Exception {
        mockMvc.perform(get("/api/einvoice/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalInvoices").value(0))
            .andExpect(jsonPath("$.certifiedCount").value(0))
            .andExpect(jsonPath("$.rejectedCount").value(0))
            .andExpect(jsonPath("$.clearanceRatePercent").value(0.0))
            .andExpect(jsonPath("$.senDriftDefectCount").value(0))
            .andExpect(jsonPath("$.totalGrossAudited").value(0.00));
    }

    @Test
    @DisplayName("Metrics reflect verified activity, arithmetic defects and gross value")
    void metricsReflectVerifiedActivity() throws Exception {
        submit(VALID_PAYLOAD);
        submit(VALID_PAYLOAD.replace("WEB-001", "WEB-003").replace("2160.00", "2160.01"));

        mockMvc.perform(get("/api/einvoice/metrics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalInvoices").value(2))
            .andExpect(jsonPath("$.certifiedCount").value(1))
            .andExpect(jsonPath("$.rejectedCount").value(1))
            .andExpect(jsonPath("$.clearanceRatePercent").value(50.0))
            .andExpect(jsonPath("$.senDriftDefectCount").value(1))
            .andExpect(jsonPath("$.totalGrossAudited").value(4320.01));
    }

    // ---------------------------------------------------------------- helpers

    private void submit(String payload) throws Exception {
        mockMvc.perform(post("/api/einvoice/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk());
    }

    private static MockMultipartFile csv(String... rows) {
        String content = CSV_HEADER + "\n" + String.join("\n", rows) + "\n";
        return new MockMultipartFile("file", "billing-batch.csv", "text/csv",
            content.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
