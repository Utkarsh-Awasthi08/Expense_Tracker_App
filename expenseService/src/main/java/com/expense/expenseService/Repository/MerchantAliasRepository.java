package com.expense.expenseService.Repository;

import com.expense.expenseService.Entities.MerchantAlias;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface MerchantAliasRepository extends CrudRepository<MerchantAlias, Long> {
    Optional<MerchantAlias> findByUserIdAndOriginalName(String userId, String originalName);
}
