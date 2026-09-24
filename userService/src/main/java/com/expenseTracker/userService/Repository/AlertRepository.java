package com.expenseTracker.userService.Repository;

import com.expenseTracker.userService.Entities.Alert;
import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface AlertRepository extends CrudRepository<Alert, Long> {
    List<Alert> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Alert> findByUserIdAndIsReadFalse(String userId);
}
