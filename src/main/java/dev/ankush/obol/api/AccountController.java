package dev.ankush.obol.api;

import dev.ankush.obol.api.AccountDtos.AccountResponse;
import dev.ankush.obol.api.AccountDtos.BalanceResponse;
import dev.ankush.obol.api.AccountDtos.CreateAccountRequest;
import dev.ankush.obol.api.TransferDtos.PostingResponse;
import dev.ankush.obol.domain.LedgerAccount;
import dev.ankush.obol.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/v1/accounts")
@Validated
@Tag(name = "Accounts", description = "The chart of accounts and its balances")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @Operation(summary = "Open an account")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        LedgerAccount account = accounts.create(
                request.code(), request.name(), request.currency(), request.type(), request.allowNegative());

        return ResponseEntity
                .created(URI.create("/v1/accounts/" + account.code()))
                .body(AccountResponse.from(account));
    }

    @GetMapping
    @Operation(summary = "List accounts")
    public List<AccountResponse> list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return accounts.list(limit, offset).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{code}")
    @Operation(summary = "Fetch one account")
    public AccountResponse get(@PathVariable String code) {
        return AccountResponse.from(accounts.findByCode(code));
    }

    @GetMapping("/{code}/balance")
    @Operation(summary = "Current balance", description = """
            Served from Redis when warm. `available` is the figure to act on: it is \
            `settled` minus anything held by pending authorisations.""")
    public BalanceResponse balance(@PathVariable String code) {
        return BalanceResponse.from(accounts.balanceOf(code));
    }

    @GetMapping("/{code}/balance/uncached")
    @Operation(summary = "Current balance, straight from Postgres", description = """
            Bypasses the cache. Exists so the cached figure can be checked against the \
            source of truth without restarting anything -- and so the benchmark can \
            measure both paths.""")
    public BalanceResponse uncachedBalance(@PathVariable String code) {
        return BalanceResponse.from(accounts.balanceFromDatabase(code));
    }

    @GetMapping("/{code}/postings")
    @Operation(summary = "Account statement", description = "Journal entries, newest first.")
    public List<PostingResponse> postings(
            @PathVariable String code,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return accounts.statement(code, limit, offset).stream().map(PostingResponse::from).toList();
    }
}
