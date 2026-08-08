package dev.ankush.obol.api;

import dev.ankush.obol.service.VerificationService;
import dev.ankush.obol.service.VerificationService.Report;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin")
@Tag(name = "Admin", description = "Integrity checks")
public class AdminController {

    private final VerificationService verification;

    public AdminController(VerificationService verification) {
        this.verification = verification;
    }

    /**
     * Returns 200 when the ledger is intact and 500 when it is not.
     *
     * <p>The status code is deliberate: this endpoint is meant to be watched.
     * A ledger that does not balance is not a report to file, it is an outage,
     * and it should page someone the same way a crashed process would.
     */
    @GetMapping("/verify")
    @Operation(summary = "Verify ledger integrity", description = """
            Recomputes every balance from the postings and sums the whole journal per \
            currency. In a correct ledger the sums are zero and no account has drifted. \
            Runs two full scans, so it is a maintenance endpoint, not a health check for \
            a load balancer -- use /actuator/health for that.""")
    public ResponseEntity<Report> verify() {
        Report report = verification.verify();
        return report.healthy()
                ? ResponseEntity.ok(report)
                : ResponseEntity.internalServerError().body(report);
    }
}
