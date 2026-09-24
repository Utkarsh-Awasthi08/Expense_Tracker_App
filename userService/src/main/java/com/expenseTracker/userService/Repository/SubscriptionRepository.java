package com.expenseTracker.userService.Repository;

import com.expenseTracker.userService.Entities.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    List<Subscription> findByUserId(String userId);
    List<Subscription> findByBillingDay(Integer billingDay);
}
