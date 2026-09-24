package com.expenseTracker.userService.Service;

import com.expenseTracker.userService.Entities.Alert;
import com.expenseTracker.userService.Entities.Subscription;
import com.expenseTracker.userService.Repository.AlertRepository;
import com.expenseTracker.userService.Repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class SubscriptionAlertCron {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private AlertRepository alertRepository;

    // Run every day at 1:00 AM
    @Scheduled(cron = "0 0 1 * * ?")
    public void generateSubscriptionAlerts() {
        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.plusDays(2);
        int targetDay = targetDate.getDayOfMonth();

        List<Subscription> upcomingSubscriptions = subscriptionRepository.findByBillingDay(targetDay);
        
        for (Subscription sub : upcomingSubscriptions) {
            Alert alert = new Alert();
            alert.setUserId(sub.getUserId());
            alert.setMessage("Upcoming Subscription: " + sub.getPlatform() + " (" + sub.getCurrency() + " " + sub.getAmount() + ") renews in 2 days.");
            alertRepository.save(alert);
        }
    }
}
