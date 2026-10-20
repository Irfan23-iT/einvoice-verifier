# e-Invoice Verifier — High Priority Hardening & Metrics Specification

**Document:** `HIGH_PRIORITY_AMENDMENTS.md`  
**Target Project:** `einvoice-verifier`[cite: 1]  
  
**Base State:** 39 tests passing, port 8080[cite: 1]  
**Workflow Standard:** Plan, Execute, and Test  

---

## 1. Overview & Objectives

This specification executes the highest-priority enhancements for the **e-Invoice Pre-Flight Verification & Batch Sanitizer**[cite: 1]:

1. **Persistent Local Database:** Switch H2 from volatile memory storage to persistent local disk storage to retain audit logs across server restarts and laptop reboots[cite: 1].
2. **Statutory Malaysian TIN Syntax Alignment:** Update TIN regex rules to recognize official LHDN alphanumeric entity prefixes (such as `C` for Companies and `IG` for Individuals)[cite: 1].
3. **Executive KPI & Metrics Engine (`GET /api/einvoice/metrics`):** Add an aggregation endpoint and render visual KPI summary cards at the top of the dashboard (Total Processed, Clearance Rate, Sen-Drifts Intercepted, and Total Amount Inspected)[cite: 1].
4. **History Table & Viewport Polish:** Add responsive horizontal scrolling and format audit timestamps to show full local calendar dates[cite: 1].
5. **Version Control Hygiene:** Exclude environment and IDE configurations (`.vscode/`, `data/`) from git tracking[cite: 1].

---

## 2. Phase 1: Plan

### 2.1 Persistence & Git Architecture
* Change Spring Datasource URL from `jdbc:h2:mem:einvoicedb` to a file-backed path under `./data/einvoicedb`[cite: 1].
* Update `.gitignore` to prevent committing binary database files (`*.mv.db`) and IDE configuration folders (`.vscode/`)[cite: 1].

### 2.2 Domain & Statutory Rule Realignment
* Update `einvoice.rules.tin-pattern` from `^[0-9]{12}$` to `^(C|IG|SG|OG|D|PT|CS)?[0-9]{10,12}$` to accommodate legitimate Malaysian business TIN registrations[cite: 1].
* Add unit test coverage in `InvoiceVerificationServiceTest` verifying prefix acceptance (`C2938475610`)[cite: 1].

### 2.3 Executive KPI Metrics Contract
* **Endpoint:** `GET /api/einvoice/metrics`
* **Response DTO (`InvoiceMetricsResponse`):**
  * `totalInvoices` (long): Total unique submissions inspected[cite: 1].
  * `certifiedCount` (long): Count of invoices with status `CERTIFIED_LHDN`[cite: 1].
  * `rejectedCount` (long): Count of invoices with status `REJECTED_INTERNAL`[cite: 1].
  * `clearanceRatePercent` (BigDecimal, 1 decimal place): Percentage of submitted invoices certified[cite: 1].
  * `senDriftDefectCount` (long): Count of rejections containing arithmetic discrepancy defects[cite: 1].
  * `totalGrossAudited` (BigDecimal, 2 decimal places): Sum of total payable amounts processed[cite: 1].

### 2.4 Frontend Dashboard UI Enhancements
* Insert a top KPI bar above the tabs in `src/main/resources/static/index.html` displaying 4 visual cards[cite: 1]:
  * **Total Audited** (Count & Gross RM Value)[cite: 1].
  * **Clearance Rate** (Percentage with green/red badge)[cite: 1].
  * **Defects Intercepted** (Count of caught arithmetic/identity discrepancies)[cite: 1].
  * **Gateway Status** (Live status indicator).
* Wrap the audit history `<table>` in an `overflow-x-auto` container with `whitespace-nowrap`[cite: 1].
* Format timestamps using `Intl.DateTimeFormat` (`DD/MM/YYYY, HH:MM:SS AM/PM`)[cite: 1].

---

## 3. Phase 2: Execute

### Step 1: Update Configuration & Git Ignore

#### `src/main/resources/application.properties`
Update persistence and validation rules[cite: 1]:
```properties
# Server
server.port=8080

# File-backed H2 Persistence
spring.datasource.url=jdbc:h2:file:./data/einvoicedb;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# Statutory Validation Rules
einvoice.rules.tin-pattern=^(C|IG|SG|OG|D|PT|CS)?[0-9]{10,12}$
einvoice.rules.buyer-id-pattern=^[0-9]{12}$
einvoice.rules.allowed-sst-rates=0.00,0.06,0.08
einvoice.rules.max-batch-rows=5000
einvoice.gateway.uuid-prefix=LHDN-MYINVOIS-
einvoice.gateway.qr-base-url=[https://myinvois.hasil.gov.my/taxpayer/validate/](https://myinvois.hasil.gov.my/taxpayer/validate/)
```

#### `.gitignore` (Root level)
Add tracking exclusions:
```gitignore
# Data persistence
data/
*.mv.db
*.trace.db

# IDE & Editor configurations
.vscode/
.idea/
*.iml
```

---

### Step 2: Implement Metrics DTO & Service Query

#### Create `InvoiceMetricsResponse.java`
Create `src/main/java/io/github/irfan23it/einvoiceverifier/dto/InvoiceMetricsResponse.java`:
```java
package io.github.irfan23it.einvoiceverifier.dto;

import java.math.BigDecimal;

public record InvoiceMetricsResponse(
    long totalInvoices,
    long certifiedCount,
    long rejectedCount,
    BigDecimal clearanceRatePercent,
    long senDriftDefectCount,
    BigDecimal totalGrossAudited
) {}
```

#### Update `InvoiceAuditRecordRepository.java`
Add custom aggregation queries in `src/main/java/io/github/irfan23it/einvoiceverifier/repository/InvoiceAuditRecordRepository.java`:
```java
package io.github.irfan23it.einvoiceverifier.repository;

import io.github.irfan23it.einvoiceverifier.domain.InvoiceAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface InvoiceAuditRecordRepository extends JpaRepository<InvoiceAuditRecord, String> {

    long countByStatus(String status);

    @Query("SELECT COUNT(a) FROM InvoiceAuditRecord a WHERE a.errorManifest LIKE '%drift%' OR a.errorManifest LIKE '%arithmetic%'")
    long countSenDriftDefects();

    @Query("SELECT COALESCE(SUM(a.totalPayable), 0) FROM InvoiceAuditRecord a")
    BigDecimal sumTotalPayable();
}
```

#### Add Metrics Computation in `InvoiceVerificationService.java`
Add the metrics retrieval method to `src/main/java/io/github/irfan23it/einvoiceverifier/service/InvoiceVerificationService.java`[cite: 1]:
```java
public InvoiceMetricsResponse getMetrics() {
    long total = auditRepository.count();
    long certified = auditRepository.countByStatus("CERTIFIED_LHDN");
    long rejected = auditRepository.countByStatus("REJECTED_INTERNAL");
    long senDrifts = auditRepository.countSenDriftDefects();
    BigDecimal totalGross = auditRepository.sumTotalPayable();

    BigDecimal clearanceRate = BigDecimal.ZERO;
    if (total > 0) {
        clearanceRate = BigDecimal.valueOf(certified)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, java.math.RoundingMode.HALF_UP);
    }

    return new InvoiceMetricsResponse(total, certified, rejected, clearanceRate, senDrifts, totalGross);
}
```

---

### Step 3: Expose Metrics API Endpoint

#### Update `InvoiceController.java`
Expose the endpoint in `src/main/java/io/github/irfan23it/einvoiceverifier/controller/InvoiceController.java`[cite: 1]:
```java
@GetMapping("/metrics")
@Operation(summary = "Get audit metrics and KPI statistics", 
           description = "Returns aggregated pre-flight validation metrics and defect counts.")
public ResponseEntity<InvoiceMetricsResponse> getMetrics() {
    return ResponseEntity.ok(verificationService.getMetrics());
}
```

---

### Step 4: Update Frontend Dashboard (`src/main/resources/static/index.html`)

1. **Insert KPI Stat Cards:** Place the KPI cards directly above the main container[cite: 1]:
```html
<!-- Executive KPI Summary Cards -->
<div class="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
  <div class="bg-white border border-slate-200 rounded-lg p-4 shadow-sm">
    <div class="text-xs uppercase font-semibold text-slate-500 tracking-wider">Total Audited</div>
    <div class="mt-2 flex items-baseline justify-between">
      <div id="kpi-total" class="text-2xl font-bold text-slate-900">0</div>
      <div id="kpi-gross" class="text-xs text-slate-500 font-medium">RM 0.00</div>
    </div>
  </div>

  <div class="bg-white border border-slate-200 rounded-lg p-4 shadow-sm">
    <div class="text-xs uppercase font-semibold text-slate-500 tracking-wider">Pre-Flight Clearance</div>
    <div class="mt-2 flex items-baseline justify-between">
      <div id="kpi-clearance" class="text-2xl font-bold text-emerald-600">0.0%</div>
      <div class="text-xs text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded font-medium">Gateway Ready</div>
    </div>
  </div>

  <div class="bg-white border border-slate-200 rounded-lg p-4 shadow-sm">
    <div class="text-xs uppercase font-semibold text-slate-500 tracking-wider">Defects Intercepted</div>
    <div class="mt-2 flex items-baseline justify-between">
      <div id="kpi-defects" class="text-2xl font-bold text-rose-600">0</div>
      <div class="text-xs text-rose-700 bg-rose-50 px-2 py-0.5 rounded font-medium">Rejected</div>
    </div>
  </div>

  <div class="bg-white border border-slate-200 rounded-lg p-4 shadow-sm">
    <div class="text-xs uppercase font-semibold text-slate-500 tracking-wider">Sen-Drifts Caught</div>
    <div class="mt-2 flex items-baseline justify-between">
      <div id="kpi-sendrift" class="text-2xl font-bold text-amber-600">0</div>
      <div class="text-xs text-amber-700 bg-amber-50 px-2 py-0.5 rounded font-medium">±0.01 Drift</div>
    </div>
  </div>
</div>
```

2. **Wrap History Table:** Wrap the table element to prevent column squishing[cite: 1]:
```html
<div class="overflow-x-auto border border-slate-200 rounded-lg">
  <table class="min-w-full divide-y divide-slate-200 text-sm">
    <!-- Headers and Body -->
  </table>
</div>
```

3. **Update JavaScript Functions:**
Update the date formatting and add KPI metric polling:
```javascript
async function loadMetrics() {
  try {
    const res = await fetch('/api/einvoice/metrics');
    if (!res.ok) return;
    const m = await res.json();
    document.getElementById('kpi-total').textContent = m.totalInvoices.toLocaleString();
    document.getElementById('kpi-gross').textContent = 'RM ' + m.totalGrossAudited.toLocaleString('en-MY', { minimumFractionDigits: 2 });
    document.getElementById('kpi-clearance').textContent = m.clearanceRatePercent + '%';
    document.getElementById('kpi-defects').textContent = m.rejectedCount.toLocaleString();
    document.getElementById('kpi-sendrift').textContent = m.senDriftDefectCount.toLocaleString();
  } catch (err) {
    console.error('Failed to load metrics', err);
  }
}

function formatDate(isoDateString) {
  if (!isoDateString) return '-';
  const d = new Date(isoDateString);
  return d.toLocaleString('en-MY', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: true
  });
}

// Invoke loadMetrics() on page initialization and after every verification or batch upload
```

---

## 4. Phase 3: Test

### Test 1: Unit & Regression Suite
Execute the Maven test suite to ensure existing tests pass alongside updated TIN regex validation[cite: 1]:
```bash
./mvnw clean test
```
*Expected Result:* All 39+ tests pass with zero failures[cite: 1].

### Test 2: Malaysian Business TIN Acceptance
Execute a single payload verification via `curl`:
```bash
curl -X POST http://localhost:8080/api/einvoice/verify \
  -H "Content-Type: application/json" \
  -d '{
    "invoiceNumber": "INV-CORP-01",
    "buyerName": "Petronas Gas Berhad",
    "buyerTin": "C2938475610",
    "buyerId": "202001048291",
    "sstRate": 0.08,
    "lineItems": [{"description": "Industrial Gas Supply", "quantity": 1, "unitPrice": 1000.00}],
    "declaredTotalPayable": 1080.00
  }'
```
*Expected Result:* Returns HTTP 200 with `status: "CERTIFIED_LHDN"`[cite: 1]. `buyerTin: "C2938475610"` is accepted without defect[cite: 1].

### Test 3: Local Persistence Verification
1. Upload `sample-billing-batch.csv` via the web UI[cite: 1].
2. Verify the 4 records appear in the history table and KPI cards update[cite: 1].
3. Shut down the application (`Ctrl + C`).
4. Verify that `./data/einvoicedb.mv.db` exists in the project root directory.
5. Restart `./mvnw spring-boot:run` and refresh `http://localhost:8080/`[cite: 1].
*Expected Result:* All 4 previously verified records and KPI metrics remain intact.

### Test 4: KPI Metric Aggregation Check
Run a query against `/api/einvoice/metrics`:
```bash
curl http://localhost:8080/api/einvoice/metrics
```
*Expected Result:* JSON payload reflects exact counts for `totalInvoices`, `certifiedCount`, `rejectedCount`, and `senDriftDefectCount`.