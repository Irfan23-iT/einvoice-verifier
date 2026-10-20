# e-Invoice Pre-Flight Verifier — Development Log

**Project:** e-Invoice Pre-Flight Verification & Batch Sanitizer

**Repository:** `https://github.com/Irfan23-iT/einvoice-verifier`
**Last updated:** 2026-10-20
**Status:** Feature-complete and tested.

---

## 1. Status at a glance

| Area | State |
| :--- | :--- |
| Build | `mvnw clean package` → BUILD SUCCESS |
| Tests | **39 passing**, 0 failures |
| Runtime | Starts on port 8080 in ~4 s |
| API | 3 endpoints implemented and exercised over HTTP |
| Dashboard | Embedded Tailwind SPA: single verify, drag-and-drop batch, audit history |
| Docs | README + Swagger UI (`/swagger-ui.html`) + this log |
| Git | 4 commits, working tree clean, **no remote configured** |

---

## 2. What the service does

It is a gatekeeper that validates Malaysian e-invoice payloads **before** they are submitted to the
LHDN MyInvois clearance gateway. It targets the two defect classes that most often cause business
billing batches to be rejected at the gateway:

1. **Degraded buyer identities** — legacy 10-digit SSM business registration numbers where the
   gateway now mandates 12 digits.
2. **Single-sen arithmetic drift** — itemised SST calculations that diverge by ±RM 0.01 from the
   invoice total payable.

Invoices can be checked individually or in bulk via a billing CSV. Every verdict is
persisted to an audit trail with the mock LHDN clearance identifier and the defect manifest.

---

## 3. Technology

| Layer | Choice | Note |
| :--- | :--- | :--- |
| Runtime | Java 21 target, Java 25 in use | |
| Framework | Spring Boot 4.1.1 | Initial spec used 3.3.x; kept 4.1.1 because it already built and ran |
| Web / persistence / validation | Spring Web MVC, Spring Data JPA, Jakarta Bean Validation | |
| Database | In-memory H2 | Zero-dependency deployment |
| CSV | Apache Commons CSV 1.11.0 | RFC 4180 parsing instead of a hand-rolled splitter |
| API docs | springdoc-openapi 3.1.1 | Upgraded from 2.5.0 — see §6 |
| Frontend | Static `index.html` + Tailwind CDN | Served by Spring Boot at `/` |

---

## 4. Commit history

| Commit | Date | Summary | Size |
| :--- | :--- | :--- | :--- |
| `43500e2` | 2026-10-19 15:55 | Complete e-Invoice pre-flight verifier and batch sanitizer | 26 files, +2288 |
| `a513f08` | 2026-10-19 16:42 | Externalise business rules, validate TIN and SST, add README | 11 files, +587/−49 |
| `c8ad02a` | 2026-10-20 09:15 | Cover the HTTP layer; close the missing-part error gap | 3 files, +296/−3 |
| `089a542` | 2026-10-20 09:34 | Isolate batch rows in their own transactions | 7 files, +142/−57 |

---

## 5. Work completed

### 5.1 Core domain and API

- `POST /api/einvoice/verify` — validates one payload and returns a verdict.
- `POST /api/einvoice/upload-batch` — multipart CSV sanitisation with per-row verdicts and totals.
- `GET /api/einvoice/history` — audit trail, newest first, optional `?status=` filter.
- DTOs: `InvoiceRequest`, `LineItemRequest`, `VerificationResponse`, `BatchUploadResponse`,
  `BatchRowResult`, `ApiError`.
- Arithmetic uses `BigDecimal` with `RoundingMode.HALF_UP` at sen (2 dp) precision:

  ```
  lineBase          = round(quantity × unitPrice)
  lineTax           = round(lineBase × sstRate)
  subtotal          = Σ lineBase
  totalTax          = Σ lineTax
  totalPayable      = subtotal + totalTax
  ```

- Certified payloads receive a mock clearance identifier (`LHDN-MYINVOIS-<UUID>`) and a QR
  verification URL. Rejected payloads receive a defect manifest and no identifier.

### 5.2 Validation and business rules

Two validation layers cooperate:

- **Structural** — Jakarta Bean Validation constraints on the request records, invoked through an
  injected `Validator` so the single-submit and batch paths behave identically.
- **Business** — TIN/identity formats, the approved SST schedule, and sen-level arithmetic.

Every rule is externalised in `application.properties` and bound by `EinvoiceProperties`:

| Rule | Property | Default |
| :--- | :--- | :--- |
| Buyer TIN format | `einvoice.rules.tin-pattern` | `^[0-9]{12}$` |
| Buyer identity (BRN / NRIC) | `einvoice.rules.buyer-id-pattern` | `^[0-9]{12}$` |
| Approved Malaysian SST rates | `einvoice.rules.allowed-sst-rates` | `0.00,0.06,0.08` |
| Maximum rows per upload | `einvoice.rules.max-batch-rows` | `5000` |
| Clearance identifier prefix | `einvoice.gateway.uuid-prefix` | `LHDN-MYINVOIS-` |
| QR verification base URL | `einvoice.gateway.qr-base-url` | `https://myinvois.hasil.gov.my/taxpayer/validate/` |

Rules can be overridden at startup without a rebuild, e.g.:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--einvoice.rules.allowed-sst-rates=0.05"
```

### 5.3 Batch pipeline

- `CsvInvoiceBatchParser` parses RFC 4180 CSV tolerantly: a bad cell becomes a row-level defect
  instead of aborting the file. Only an unusable file (missing header columns, unreadable stream)
  raises an error.
- `InvoiceBatchSanitizer` enforces the row limit and loops over rows. It is deliberately
  **non-transactional**.
- `InvoiceVerificationService.processRow` runs each row in a `REQUIRES_NEW` transaction, so rows
  commit or roll back independently. An unexpected row failure is logged, reported in place as
  `REJECTED_INTERNAL`, and does not discard rows already processed.

### 5.4 Audit trail

- `InvoiceAuditRecord` is H2-backed and keyed on `invoiceNumber`.
- Stores status, error manifest, total payable, mock LHDN UUID, QR URL, `createdAt`,
  `lastVerifiedAt`, and `attemptCount`.
- Repeat submissions **refresh** every mutable field (via `applyVerification`) rather than mixing
  old and new values, and increment `attemptCount`.

### 5.5 Dashboard

- Single-invoice form with one-click sample data.
- Drag-and-drop / file-picker CSV upload with a per-row result table.
- Audit history table with status metrics.
- All server-provided values are HTML-escaped before rendering.

### 5.6 Documentation and tooling

- `README.md`: setup, endpoints with `curl`, CSV schema, rules table, arithmetic model,
  architecture map, limitations.
- Swagger UI with a titled OpenAPI document.
- Sample batch at `src/main/resources/static/sample-billing-batch.csv` (2 clean, 2 defective rows).

---

## 6. Defects found and fixed

| # | Symptom | Cause | Fix |
| :--- | :--- | :--- | :--- |
| 1 | Re-submitting an invoice left a stale/contradictory audit record | Upsert path only updated a subset of fields | `InvoiceAuditRecord.applyVerification(...)` now refreshes every mutable field and increments `attemptCount` |
| 2 | Null or malformed payloads returned HTTP 500 | Arithmetic dereferenced null amounts before validation ran | Structural validation runs first; only complete payloads reach the maths |
| 3 | `GET /v3/api-docs` returned HTTP 500 | springdoc 2.5.0 called `ControllerAdviceBean(Object)`, removed in Spring Framework 7 | Upgraded to springdoc 3.1.1 and added OpenAPI metadata |
| 4 | Multipart request with no `file` part returned Spring's default error page | `MissingServletRequestPartException` was unmapped | `GlobalExceptionHandler` now returns the `ApiError` envelope |
| 5 | One unexpected row failure rolled back the whole batch | Entire file ran in a single transaction | Per-row `REQUIRES_NEW` transactions + in-place row error reporting |
| 6 | `buyerTin` accepted any non-blank string | Only presence was validated, though the spec requires a Malaysian TIN format | Configurable TIN pattern, default 12 digits |
| 7 | Any `sstRate` between 0 and 1 was accepted | No schedule check | Approved-rate check (`0.00 / 0.06 / 0.08` by default) |
| 8 | Dashboard rendered server values as raw HTML | Unescaped interpolation | `escapeHtml(...)` on every server-provided value |
| 9 | Verdict status was `"CERTIFIED_LHDN (UUID: ...)"` | Identifier embedded in the status string | Split into `status`, `lhdnUuid`, `qrCodeUrl` |

---

## 7. Test coverage — 39 tests

| Suite | Tests | Covers |
| :--- | ---: | :--- |
| `InvoiceVerificationServiceTest` | 16 | Rounding (HALF_UP), 10-digit BRN, TIN format, SST schedule, multi-item totals, null safety, empty items, blank invoice number, audit refresh on resubmission, configurable TIN/SST rules |
| `InvoiceBatchSanitizerTest` | 8 | Row parsing, quoted fields, non-numeric cells, missing columns, mixed batch split, malformed-row persistence, missing invoice number, row limit |
| `InvoiceControllerTest` | 14 | `/verify` certified/rejected, malformed and empty JSON, wrong field types, batch split, non-CSV/empty/missing file, bad header, history ordering/filtering, **per-row isolation** |
| `EinvoiceVerifierApplicationTests` | 1 | Context loads (binds `EinvoiceProperties`) |

Run with:

```bash
cd einvoice-verifier
./mvnw test
```

---

## 8. Verification evidence

All of the following were exercised against the running app, not just unit-tested:

| Scenario | Observed result |
| :--- | :--- |
| Valid 8% payload | `CERTIFIED_LHDN` + UUID + QR URL |
| 10-digit BRN + 1-sen drift | `REJECTED_INTERNAL` with 2 defects |
| Re-submit the same invoice | `attemptCount: 2`, manifest refreshed |
| Sample 4-row CSV | 2 certified / 2 intercepted |
| Malformed JSON | HTTP 400, `REJECTED_INTERNAL` envelope |
| Bad CSV header / wrong extension / empty file | HTTP 400, `REQUEST_REJECTED` envelope |
| 11-character TIN (`C2938475610`) | Rejected with the TIN format rule |
| SST `0.10` | Rejected as an unapproved rate |
| SST `0.06` | Certified |
| Startup override (`allowed-sst-rates=0.05`) | 8% rejected, 5% certified — proves rules bind from properties |
| Batch row 2 forced to fail at commit | Row 1 stayed committed, row 2 reported in place |

---

## 9. Deliberate scope decisions

| Decision | Rationale |
| :--- | :--- |
| Spring Boot 4.1.1 instead of spec's 3.3.x | The project already built and ran; downgrading risked breaking a working baseline |
| One line item per invoice / CSV row | The CSV schema is flat; multi-line invoices are not modelled |
| QR is a verification **URL**, not an image | The spec requires a QR verification URL |
| Validation invoked manually, not via `@Valid` on the controller | Keeps the single-submit and batch paths identical and the response shape uniform |
| Problem-details advice disabled | Boot's own advice outranked the custom handler and changed the error shape |
| In-memory H2 | Keeps the zero-dependency deployment; resets on restart |

---

## 10. Known limitations

- The LHDN gateway is simulated; identifiers and QR URLs are synthetic.
- No authentication, authorisation or rate limiting.
- The audit trail does not survive a restart (in-memory database).
- One line item per invoice payload and per CSV row.
- History is unpaginated.
- The dashboard history table squeezes columns on narrow viewports, and "Last Verified" shows time
  without a date.
- Repository has no remote; nothing has been pushed.
- `.vscode/settings.json` is tracked at the repository root because the project's `.gitignore`
  only covers the nested Maven directory.

---

## 11. Next steps

| Priority | Item | Notes |
| :--- | :--- | :--- |
| 1 | Render a scannable QR image | Currently the dashboard shows only the verification URL |
| 2 | UI polish | Horizontal scroll / nowrap on the history table; show date + time |
| 3 | Configure a git remote and push | `origin` is not set |
| 4 | Optional | Pagination for `/history`, audit CSV export, multi-line invoices |

---

## 12. How to run and demo

```bash
cd einvoice-verifier
./mvnw spring-boot:run
```

| URL | Purpose |
| :--- | :--- |
| <http://localhost:8080/> | Dashboard |
| <http://localhost:8080/swagger-ui.html> | Interactive API docs |
| <http://localhost:8080/h2-console> | H2 console (`jdbc:h2:mem:einvoicedb`, `sa`, blank password) |

Suggested demo flow:

1. Open the dashboard and click **Load Sample** → submit → `CERTIFIED_LHDN`.
2. Load the defective sample → submit → rejection with the sen drift explained.
3. Re-submit the same invoice → the audit row shows `attemptCount: 2`.
4. Download and re-upload `sample-billing-batch.csv` → 2 certified / 2 intercepted.
5. Open Swagger UI to show the same three endpoints as a documented contract.
