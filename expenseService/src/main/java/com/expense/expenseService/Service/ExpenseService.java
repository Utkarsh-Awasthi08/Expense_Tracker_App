package com.expense.expenseService.Service;

import com.expense.expenseService.DTO.CreateExpenseRequest;
import com.expense.expenseService.DTO.ExpenseDTO;
import com.expense.expenseService.DTO.ExpenseItem;
import com.expense.expenseService.DTO.ExpensePage;
import com.expense.expenseService.DTO.LegacyExpenseItem;
import com.expense.expenseService.Entities.Amounts;
import com.expense.expenseService.Entities.Expense;
import com.expense.expenseService.Entities.ExpenseCategory;
import com.expense.expenseService.Entities.TxnType;
import com.expense.expenseService.Mapper.ExpenseMapper;
import com.expense.expenseService.Repository.ExpenseRepository;
import com.expense.expenseService.Repository.MerchantAliasRepository;
import com.expense.expenseService.Entities.MerchantAlias;
import com.expense.expenseService.Web.FieldIssue;
import com.expense.expenseService.Web.RequestValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

@Service
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    /** Largest page the list endpoint serves. */
    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** Rows returned by the legacy alias; must match ExpenseRepository.findTop500... */
    public static final int LEGACY_LIMIT = 500;
    public static final int MAX_MERCHANT_LENGTH = 255;

    /** MySQL DATE holds 1000-01-01..9999-12-31; a narrower, sane window also keeps parameters well inside it. */
    public static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    public static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("txnDate"), Sort.Order.desc("id"));

    private final ExpenseRepository expenseRepository;
    private final MerchantAliasRepository merchantAliasRepository;

    private final ExpenseMapper expenseMapper;

    private final Clock clock;

    @Autowired
    ExpenseService(ExpenseRepository expenseRepository, MerchantAliasRepository merchantAliasRepository, ExpenseMapper expenseMapper) {
        this(expenseRepository, merchantAliasRepository, expenseMapper, Clock.systemUTC());
    }

    ExpenseService(ExpenseRepository expenseRepository, MerchantAliasRepository merchantAliasRepository, ExpenseMapper expenseMapper, Clock clock) {
        this.expenseRepository = expenseRepository;
        this.merchantAliasRepository = merchantAliasRepository;
        this.expenseMapper = expenseMapper;
        this.clock = clock;
    }

    /**
     * Stores the expense. Returns {@code false} (after logging the reason at WARN/ERROR) when it was rejected:
     * missing user_id / txn_date, invalid amount, or a database constraint such as a duplicate
     * (user_id, sms_hash). Logs identify the row by ids and hash only, never by amount or merchant.
     */
    public boolean createExpense(ExpenseDTO expenseDTO) {
        if (expenseDTO == null) {
            log.warn("Expense rejected: no payload");
            return false;
        }
        try {
            requireCreatable(expenseDTO);
            
            if (StringUtils.hasText(expenseDTO.getMerchant())) {
                String originalMerchant = expenseDTO.getMerchant().trim();
                expenseDTO.setMerchant(originalMerchant);
                merchantAliasRepository.findByUserIdAndOriginalName(expenseDTO.getUserId(), originalMerchant)
                        .ifPresent(alias -> expenseDTO.setMerchant(alias.getAliasName()));
            }
            
            expenseRepository.save(expenseMapper.toEntity(expenseDTO));
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Expense rejected as invalid (user_id={}, external_id={}, sms_hash={}): {}",
                    expenseDTO.getUserId(), expenseDTO.getExternalId(), expenseDTO.getSmsHash(), e.getMessage());
            return false;
        } catch (DataIntegrityViolationException e) {
            log.warn("Expense rejected by a database constraint (user_id={}, external_id={}, sms_hash={}): {}",
                    expenseDTO.getUserId(), expenseDTO.getExternalId(), expenseDTO.getSmsHash(),
                    NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("Failed to create expense (user_id={}, external_id={}, sms_hash={})",
                    expenseDTO.getUserId(), expenseDTO.getExternalId(), expenseDTO.getSmsHash(), e);
            return false;
        }
    }

    /**
     * Patches currency / merchant / amount of an existing expense, identified by (user_id, external_id).
     * Same contract as {@link #createExpense}: returns {@code false} after logging when nothing was updated
     * (null payload, missing ids, no such expense, invalid amount, database rejection), never throws for those.
     */
    public boolean updateExpense(ExpenseDTO expenseDTO) {
        if (expenseDTO == null) {
            log.warn("Expense update rejected: no payload");
            return false;
        }
        if (!StringUtils.hasText(expenseDTO.getUserId()) || !StringUtils.hasText(expenseDTO.getExternalId())) {
            log.warn("Expense update rejected: user_id and external_id are required (user_id={}, external_id={})",
                    expenseDTO.getUserId(), expenseDTO.getExternalId());
            return false;
        }
        try {
            Optional<Expense> existingExpense = expenseRepository.findByUserIdAndExternalId(
                    expenseDTO.getUserId(), expenseDTO.getExternalId());

            if (existingExpense.isEmpty()) {
                log.warn("Expense update ignored, no such expense (user_id={}, external_id={})",
                        expenseDTO.getUserId(), expenseDTO.getExternalId());
                return false;
            }

            Expense expense = existingExpense.get();
            if (StringUtils.hasText(expenseDTO.getCurrency())) {
                expense.setCurrency(expenseDTO.getCurrency());
            }
            if (StringUtils.hasText(expenseDTO.getMerchant())) {
                expense.setMerchant(expenseDTO.getMerchant());
            }
            if (expenseDTO.getAmount() != null) {
                // throws IllegalArgumentException for a non-positive or out-of-range amount
                expense.setAmount(expenseDTO.getAmount());
            }
            expenseRepository.save(expense);
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Expense update rejected as invalid (user_id={}, external_id={}): {}",
                    expenseDTO.getUserId(), expenseDTO.getExternalId(), e.getMessage());
            return false;
        } catch (DataIntegrityViolationException e) {
            log.warn("Expense update rejected by a database constraint (user_id={}, external_id={}): {}",
                    expenseDTO.getUserId(), expenseDTO.getExternalId(),
                    NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("Failed to update expense (user_id={}, external_id={})",
                    expenseDTO.getUserId(), expenseDTO.getExternalId(), e);
            return false;
        }
    }

    /**
     * The caller's expenses, newest transaction first (txn_date desc, id desc), optionally restricted to an
     * inclusive txn_date range. {@code userId} comes from the identity filter and scopes every query.
     *
     * @throws RequestValidationException for a negative or absurdly large page, a size outside 1..200, from after to, or dates
     *                                    outside the supported window
     */
    public ExpensePage list(String userId, LocalDate from, LocalDate to, int page, int size) {
        List<FieldIssue> issues = new ArrayList<>();
        if (page < 0) {
            issues.add(new FieldIssue("page", "must be 0 or greater"));
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            issues.add(new FieldIssue("size", "must be between 1 and " + MAX_PAGE_SIZE));
        }
        if (page >= 0 && size >= 1 && (long) page * size > Integer.MAX_VALUE) {
            // Spring Data narrows the row offset to an int and would answer 500 for anything beyond it.
            issues.add(new FieldIssue("page", "is too large"));
        }
        requireDateInWindow("from", from, issues);
        requireDateInWindow("to", to, issues);
        if (from != null && to != null && from.isAfter(to)) {
            issues.add(new FieldIssue("from", "must not be after 'to'"));
        }
        if (!issues.isEmpty()) {
            throw new RequestValidationException(issues);
        }

        Pageable pageable = PageRequest.of(page, size, LIST_ORDER);
        Page<Expense> result = expenseRepository.search(userId, from, to, pageable);
        List<ExpenseItem> items = result.getContent().stream().map(expenseMapper::toItem).toList();
        return new ExpensePage(items, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    /** Legacy alias: the caller's newest {@value #LEGACY_LIMIT} rows in the old, minimal shape. */
    public List<LegacyExpenseItem> legacyList(String userId) {
        return expenseRepository.findTop500ByUserIdOrderByTxnDateDescIdDesc(userId).stream()
                .map(expenseMapper::toLegacyItem)
                .toList();
    }

    public java.math.BigDecimal currentMonthTotal(String userId) {
        LocalDate startOfMonth = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        return expenseRepository.sumAmountByUserIdAndTxnDateGreaterThanEqual(userId, startOfMonth);
    }

    public java.math.BigDecimal dailyTotal(String userId, LocalDate date) {
        return expenseRepository.sumAmountByUserIdAndTxnDate(userId, date);
    }

    /**
     * Creates an expense owned by {@code userId} (always the authenticated caller, never anything from the body).
     * Defaults: currency INR, category OTHER, txn_type DEBIT, txn_date today (UTC).
     *
     * @throws RequestValidationException listing every invalid field
     */
    public ExpenseItem create(String userId, CreateExpenseRequest request) {
        if (request == null) {
            throw new RequestValidationException(List.of(new FieldIssue("body", "is required")));
        }
        List<FieldIssue> issues = validate(request);
        if (!issues.isEmpty()) {
            throw new RequestValidationException(issues);
        }

        ExpenseDTO dto = ExpenseDTO.builder()
                .userId(userId)
                .amount(request.amount())
                .currency(request.currency() != null ? request.currency() : Expense.DEFAULT_CURRENCY)
                .merchant(StringUtils.hasText(request.merchant()) ? request.merchant().trim() : null)
                .category(request.category() != null ? request.category() : Expense.DEFAULT_CATEGORY)
                .txnType(request.txnType() != null ? request.txnType() : Expense.DEFAULT_TXN_TYPE)
                .txnDate(request.txnDate() != null ? request.txnDate() : LocalDate.now(clock.withZone(ZoneOffset.UTC)))
                .build();
                
        if (StringUtils.hasText(dto.getMerchant())) {
            merchantAliasRepository.findByUserIdAndOriginalName(userId, dto.getMerchant())
                    .ifPresent(alias -> dto.setMerchant(alias.getAliasName()));
        }

        // externalId / createdAt are left null on purpose: the entity generates them, the client never supplies them.
        Expense saved = expenseRepository.save(expenseMapper.toEntity(dto));
        return expenseMapper.toItem(saved);
    }

    private static List<FieldIssue> validate(CreateExpenseRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        try {
            Amounts.normalize(request.amount());
        } catch (IllegalArgumentException e) {
            issues.add(new FieldIssue("amount", e.getMessage()));
        }
        if (request.currency() != null && !isIsoCurrency(request.currency())) {
            issues.add(new FieldIssue("currency", "must be a valid ISO-4217 currency code, e.g. INR"));
        }
        if (request.category() != null && !ExpenseCategory.isValid(request.category())) {
            issues.add(new FieldIssue("category", "must be one of " + ExpenseCategory.allowedValues()));
        }
        if (request.txnType() != null && !TxnType.isValid(request.txnType())) {
            issues.add(new FieldIssue("txn_type", "must be one of " + TxnType.allowedValues()));
        }
        if (request.merchant() != null) {
            String merchant = request.merchant().trim();
            if (merchant.codePointCount(0, merchant.length()) > MAX_MERCHANT_LENGTH) {
                issues.add(new FieldIssue("merchant", "must be at most " + MAX_MERCHANT_LENGTH + " characters"));
            }
        }
        requireDateInWindow("txn_date", request.txnDate(), issues);
        return issues;
    }

    private static void requireDateInWindow(String field, LocalDate date, List<FieldIssue> issues) {
        if (date != null && (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE))) {
            issues.add(new FieldIssue(field, "must be between " + MIN_DATE + " and " + MAX_DATE));
        }
    }

    private static boolean isIsoCurrency(String code) {
        if (code.length() != 3) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            char c = code.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireCreatable(ExpenseDTO expenseDTO) {
        if (!StringUtils.hasText(expenseDTO.getUserId())) {
            throw new IllegalArgumentException("user_id is required");
        }
        if (expenseDTO.getTxnDate() == null) {
            throw new IllegalArgumentException("txn_date is required");
        }
    }

    @Transactional
    public void createOrUpdateAlias(String userId, String originalName, String aliasName) {
        if (!StringUtils.hasText(originalName) || !StringUtils.hasText(aliasName)) return;
        originalName = originalName.trim();
        aliasName = aliasName.trim();
        
        Optional<MerchantAlias> existing = merchantAliasRepository.findByUserIdAndOriginalName(userId, originalName);
        if (existing.isPresent()) {
            MerchantAlias alias = existing.get();
            alias.setAliasName(aliasName);
            merchantAliasRepository.save(alias);
        } else {
            merchantAliasRepository.save(new MerchantAlias(userId, originalName, aliasName));
        }
        
        expenseRepository.updateMerchantName(userId, originalName, aliasName);
    }
}
