# e-Invoice Pre-Flight Verification & Batch Sanitizer

A gatekeeper microservice that validates Malaysian e-invoice payloads **before** they are submitted
to the LHDN MyInvois clearance gateway. It catches the two defect classes that most often cause
billing batches to be rejected at the gateway:

1. **Degraded buyer identities** — 10-digit legacy SSM business registration numbers where the
   gateway now mandates 12 digits.
2. **Single-sen arithmetic drift** — itemised SST calculations that diverge by ±RM 0.01 from the
   invoice total payable.

Invoices can be checked one at a time, or in bulk by uploading a billing CSV dump.
Every verdict is persisted to an audit trail with the LHDN clearance identifier and any defects found.

---

## Tech stack

| Layer | Choice |
| :--- | :--- |
| Runtime | Java 21 (build target), Spring Boot 4.1.1 |
| Web / persistence / validation | Spring Web MVC, Spring Data JPA, Jakarta Bean Validation |
| Database | In-memory H2 (zero-dependency deployment) |
| CSV parsing | Apache Commons CSV (RFC 4180) |
| API docs | springdoc-openapi / Swagger UI |
| Frontend | Embedded single-page dashboard, Tailwind CSS |

---

## Running it

The Maven project is nested one level below the repository root, so run the wrapper from there:

```bash
cd einvoice-verifier
./mvnw spring-boot:run
```

Then open:

| URL | What it is |
| :--- | :--- |
| <http://localhost:8080/> | Verification dashboard (single + batch) |
| <http://localhost:8080/swagger-ui.html> | Interactive API documentation |
| <http://localhost:8080/h2-console> | H2 console (JDBC URL `jdbc:h2:mem:einvoicedb`, user `sa`, blank password) |

The database is in-memory, so all audit records reset when the service restarts.

---

## API

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/einvoice/verify` | Validate a single invoice payload and issue a mock LHDN certificate |
| `POST` | `/api/einvoice/upload-batch` | Sanitise a CSV billing file (`multipart/form-data`) |
| `GET` | `/api/einvoice/history` | Audit trail, newest first; optional `?status=` filter |

### Verify a single invoice

```bash
curl -s -X POST http://localhost:8080/api/einvoice/verify \
  -H 'Content-Type: application/json' \
  -d '{
    "invoiceNumber": "INV-2026-001",
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
  }'
```

A certified payload returns:

```json
{
  "invoiceNumber": "INV-2026-001",
  "status": "CERTIFIED_LHDN",
  "errorCount": 0,
  "errors": [],
  "lhdnUuid": "LHDN-MYINVOIS-3FE6E9A1-D2A5-4E67-9891-F07AE0E07B28",
  "qrCodeUrl": "https://myinvois.hasil.gov.my/taxpayer/validate/LHDN-MYINVOIS-3FE6E9A1-..."
}
```

A rejected payload returns `status: "REJECTED_INTERNAL"` with `errorCount` and an `errors` array
describing every defect found in that pass.

### Sanitise a batch

```bash
curl -s -X POST http://localhost:8080/api/einvoice/upload-batch \
  -F 'file=@src/main/resources/static/sample-billing-batch.csv'
```

Returns per-row verdicts plus totals:

```json
{
  "fileName": "sample-billing-batch.csv",
  "totalRows": 4,
  "certified": 2,
  "rejected": 2,
  "rows": [ { "row": 1, "invoiceNumber": "INV-2026-001", "status": "CERTIFIED_LHDN",
              "errorCount": 0, "errors": [], "lhdnUuid": "LHDN-MYINVOIS-..." } ]
}
```

### Audit trail

```bash
curl -s http://localhost:8080/api/einvoice/history
curl -s 'http://localhost:8080/api/einvoice/history?status=REJECTED_INTERNAL'
```

---

## CSV batch schema

One line item per row, with the header written exactly as follows (column order is not significant):

```csv
invoiceNumber,buyerName,buyerIdType,buyerIdValue,buyerTin,desc,qty,price,sstRate,lineTotal,subtotal,totalTax,totalPayable
```

| Column | Meaning |
| :--- | :--- |
| `invoiceNumber` | Invoice reference; the audit trail is keyed on this |
| `buyerName` | Buyer legal name |
| `buyerIdType` | `BRN` (SSM company) or `NRIC` (individual MyKad) |
| `buyerIdValue` | 12-digit identity number, no dashes |
| `buyerTin` | Malaysian Tax Identification Number |
| `desc` | Line item description |
| `qty` / `price` | Quantity and unit price |
| `sstRate` | Service tax rate as a decimal fraction, e.g. `0.08` for 8% |
| `lineTotal` | Submitted line total including tax |
| `subtotal` / `totalTax` / `totalPayable` | Submitted invoice header totals |

A sample file is bundled at `src/main/resources/static/sample-billing-batch.csv` and is downloadable
from the dashboard. It deliberately contains two clean rows and two defective rows.

Parsing is tolerant: a malformed cell is reported as that row's defect rather than aborting the
whole file. Only an unusable file (missing header columns, unreadable stream) is rejected outright.

---

## Business rules

Every rule below is externalised and can be tuned to match production specifications **without a
code change**. Defaults are in `src/main/resources/application.properties`.

| Rule | Property | Default |
| :--- | :--- | :--- |
| Buyer TIN format | `einvoice.rules.tin-pattern` | `^[0-9]{12}$` |
| Buyer identity (BRN / NRIC) | `einvoice.rules.buyer-id-pattern` | `^[0-9]{12}$` |
| Approved Malaysian SST rates | `einvoice.rules.allowed-sst-rates` | `0.00,0.06,0.08` |
| Maximum rows per upload | `einvoice.rules.max-batch-rows` | `5000` |
| Clearance identifier prefix | `einvoice.gateway.uuid-prefix` | `LHDN-MYINVOIS-` |
| QR verification base URL | `einvoice.gateway.qr-base-url` | `https://myinvois.hasil.gov.my/taxpayer/validate/` |

### Arithmetic model

All monetary maths uses `BigDecimal` with `RoundingMode.HALF_UP` and sen-level (2 decimal place)
precision:

```
lineBase          = round(quantity × unitPrice)
lineTax           = round(lineBase × sstRate)
expectedLineTotal = lineBase + lineTax
subtotal          = Σ lineBase
totalTax          = Σ lineTax
totalPayable      = subtotal + totalTax
```

Any divergence between a submitted value and its calculated counterpart rejects the invoice.
Note that `totalPayable` is derived from the **rounded** line values, so a correct invoice is one
whose total equals `subtotal + totalTax`, not the unrounded sum of the inputs.

### Verdicts

| Status | Meaning |
| :--- | :--- |
| `CERTIFIED_LHDN` | Cleared every rule; a mock clearance identifier and QR URL were issued |
| `REJECTED_INTERNAL` | Intercepted before submission; see `errors` for the defect manifest |

---

## Architecture

```
config/
  EinvoiceProperties.java       Externalised business rules (see table above)
  OpenApiConfig.java            Swagger metadata
controller/
  InvoiceController.java        /verify, /upload-batch, /history
  GlobalExceptionHandler.java   Single error contract for framework failures
dto/
  InvoiceRequest.java           Single-submission payload (+ validation constraints)
  LineItemRequest.java          Line item (+ validation constraints)
  VerificationResponse.java     Verdict
  BatchUploadResponse.java      Batch totals + per-row verdicts
  BatchRowResult.java           One CSV row's verdict
  ApiError.java                 Non-verdict failures (e.g. bad upload)
entity/
  InvoiceAuditRecord.java       Persisted audit row
repository/
  InvoiceAuditRepository.java   JPA repository
service/
  InvoiceVerificationService.java   Rule evaluation, arithmetic, batch orchestration
  CsvInvoiceBatchParser.java        Tolerant CSV -> InvoiceRequest mapping
```

Two layers of validation cooperate:

- **Structural** — Jakarta Bean Validation constraints on the request records, enforced through an
  injected `Validator` so the single-submit and batch paths behave identically. This guarantees the
  arithmetic below never dereferences a null.
- **Business** — TIN and identity formats, the approved SST schedule, and sen-level arithmetic,
  evaluated in `InvoiceVerificationService`.

The audit trail is upserted on `invoiceNumber`. A repeat submission refreshes every mutable field
(including the defect manifest) and increments `attemptCount`, so a stored record always describes
the latest attempt rather than a mixture of old and new values.

---

## Tests

```bash
cd einvoice-verifier
./mvnw test
```

38 tests cover sen-level rounding, identity and TIN rules, the SST schedule, configuration
overrides, null-safety, audit-trail refresh on resubmission, CSV parsing edge cases, batch
aggregation, and the HTTP contract itself (status codes, malformed JSON, multipart handling and
history filtering).

---

## Known limitations

- The LHDN gateway is simulated: clearance identifiers and QR URLs are synthetically generated and
  not registered with any real system.
- One line item per invoice (JSON) and per CSV row; multi-line invoices are not yet modelled.
- The audit database is in-memory and does not survive a restart.
- OpenAPI documentation is intentionally enabled; set `springdoc.api-docs.enabled=false` and
  `springdoc.swagger-ui.enabled=false` for a locked-down deployment.
