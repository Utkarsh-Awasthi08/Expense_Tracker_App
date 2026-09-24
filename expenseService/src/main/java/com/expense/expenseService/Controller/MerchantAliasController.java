package com.expense.expenseService.Controller;

import com.expense.expenseService.DTO.MerchantAliasRequest;
import com.expense.expenseService.Identity.CurrentUserId;
import com.expense.expenseService.Service.ExpenseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/expense/v1/merchant-alias")
public class MerchantAliasController {

    private final ExpenseService expenseService;

    @Autowired
    public MerchantAliasController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    @PostMapping
    public ResponseEntity<Void> createOrUpdateAlias(@CurrentUserId String userId, @RequestBody MerchantAliasRequest request) {
        expenseService.createOrUpdateAlias(userId, request.originalName(), request.aliasName());
        return ResponseEntity.ok().build();
    }
}
