package com.expense.expenseService.DTO;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** {@code {"items":[...],"page":n,"size":n,"total_elements":n,"total_pages":n}} */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExpensePage(List<ExpenseItem> items, int page, int size, long totalElements, int totalPages) {
}
