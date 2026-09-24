package com.expense.expenseService.Mapper;

import com.expense.expenseService.DTO.ExpenseDTO;
import com.expense.expenseService.DTO.ExpenseItem;
import com.expense.expenseService.DTO.LegacyExpenseItem;
import com.expense.expenseService.Entities.Expense;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * Explicit, field-by-field mapping between the snake_case {@link ExpenseDTO} and the {@link Expense} entity.
 * <p>
 * This replaces a reflective {@code ObjectMapper.convertValue}, which silently dropped every field whose name
 * differed between the two classes (user_id, external_id, created_at). Every field must be mapped in both
 * directions; {@code ExpenseMapperTest} fails if a field is added to one side and forgotten here.
 * <p>
 * Persistence-managed values that have no DTO counterpart ({@code id}, {@code updatedAt}) are never mapped.
 */
@Component
public class ExpenseMapper {

    /**
     * Blank currency/category/txn_type fall back to the entity defaults. A null or non-positive amount
     * makes {@link Expense#setAmount} throw {@link IllegalArgumentException}.
     */
    public Expense toEntity(ExpenseDTO dto) {
        Objects.requireNonNull(dto, "dto");
        Expense entity = new Expense();
        entity.setExternalId(dto.getExternalId());
        entity.setUserId(dto.getUserId());
        entity.setSmsHash(dto.getSmsHash());
        entity.setSmsReceivedAt(dto.getSmsReceivedAt());
        entity.setAmount(dto.getAmount());
        if (StringUtils.hasText(dto.getCurrency())) {
            entity.setCurrency(dto.getCurrency());
        }
        entity.setMerchant(dto.getMerchant());
        if (StringUtils.hasText(dto.getCategory())) {
            entity.setCategory(dto.getCategory());
        }
        if (StringUtils.hasText(dto.getTxnType())) {
            entity.setTxnType(dto.getTxnType());
        }
        entity.setAccountLast4(dto.getAccountLast4());
        entity.setTxnDate(dto.getTxnDate());
        entity.setCreatedAt(dto.getCreatedAt());
        return entity;
    }

    public ExpenseDTO toDto(Expense entity) {
        Objects.requireNonNull(entity, "entity");
        return ExpenseDTO.builder()
                .externalId(entity.getExternalId())
                .userId(entity.getUserId())
                .smsHash(entity.getSmsHash())
                .smsReceivedAt(entity.getSmsReceivedAt())
                .amount(entity.getAmount())
                .currency(entity.getCurrency())
                .merchant(entity.getMerchant())
                .category(entity.getCategory())
                .txnType(entity.getTxnType())
                .accountLast4(entity.getAccountLast4())
                .txnDate(entity.getTxnDate())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * The API view of a row: no user_id, no sms_hash. Add a field here on purpose or not at all; the API tests
     * pin the exact property set.
     */
    public ExpenseItem toItem(Expense entity) {
        Objects.requireNonNull(entity, "entity");
        return new ExpenseItem(
                entity.getExternalId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getMerchant(),
                entity.getCategory(),
                entity.getTxnType(),
                entity.getAccountLast4(),
                entity.getTxnDate(),
                entity.getCreatedAt());
    }

    /** Legacy alias row; {@code created_at} is {@code coalesce(sms_received_at, created_at)}. */
    public LegacyExpenseItem toLegacyItem(Expense entity) {
        Objects.requireNonNull(entity, "entity");
        return new LegacyExpenseItem(
                entity.getAmount(),
                entity.getMerchant(),
                entity.getCurrency(),
                entity.getSmsReceivedAt() != null ? entity.getSmsReceivedAt() : entity.getCreatedAt());
    }
}
