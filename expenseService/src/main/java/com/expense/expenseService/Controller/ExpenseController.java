package com.expense.expenseService.Controller;

import com.expense.expenseService.DTO.CreateExpenseRequest;
import com.expense.expenseService.DTO.ExpenseItem;
import com.expense.expenseService.DTO.ExpensePage;
import com.expense.expenseService.DTO.LegacyExpenseItem;
import com.expense.expenseService.Identity.CurrentUserId;
import com.expense.expenseService.Service.ExpenseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Expense API. The owner of every row is the caller identified by {@link CurrentUserId} (the gateway-provided,
 * filter-validated X-User-Id); no endpoint accepts a user id in a query parameter or a body.
 * Errors are rendered by {@code GlobalExceptionHandler} in the pinned error shape.
 */
@RestController
@RequestMapping("/expense/v1")
public class ExpenseController {

    private final ExpenseService expenseService;

    @Autowired
    ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    /** {@code GET /expense/v1/expenses?from&to&page&size}: from/to are optional, inclusive ISO dates on txn_date. */
    @GetMapping("/expenses")
    public ExpensePage list(
            @CurrentUserId String userId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + ExpenseService.DEFAULT_PAGE_SIZE) int size) {
        return expenseService.list(userId, from, to, page, size);
    }

    /** {@code POST /expense/v1/expenses}: creates an expense owned by the caller; answers 201 with the item. */
    @PostMapping("/expenses")
    public ResponseEntity<ExpenseItem> create(@CurrentUserId String userId, @RequestBody CreateExpenseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(expenseService.create(userId, request));
    }

    /**
     * Legacy alias for the untouched mobile app: a BARE array (no wrapper) of the caller's newest 500 rows.
     * Kept until the app moves to {@code /expenses}.
     */
    @GetMapping("/getExpense")
    public List<LegacyExpenseItem> legacyList(@CurrentUserId String userId) {
        return expenseService.legacyList(userId);
    }

    @GetMapping("/currentMonthTotal")
    public java.math.BigDecimal currentMonthTotal(@CurrentUserId String userId) {
        return expenseService.currentMonthTotal(userId);
    }

    @GetMapping("/dailyTotal")
    public java.math.BigDecimal getDailyTotal(@CurrentUserId String userId, @RequestParam String date) {
        return expenseService.dailyTotal(userId, LocalDate.parse(date));
    }
}
