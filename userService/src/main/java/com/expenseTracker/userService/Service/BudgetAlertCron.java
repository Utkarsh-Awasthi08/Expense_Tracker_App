package com.expenseTracker.userService.Service;

import com.expenseTracker.userService.Entities.Alert;
import com.expenseTracker.userService.Entities.UserInfo;
import com.expenseTracker.userService.Repository.AlertRepository;
import com.expenseTracker.userService.Repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class BudgetAlertCron {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertCron.class);

    private final UserRepository userRepository;
    private final AlertRepository alertRepository;
    private final RestTemplate restTemplate;

    @Value("${EXPENSE_SERVICE_URL:http://localhost:9820}")
    private String expenseServiceUrl;

    public BudgetAlertCron(UserRepository userRepository, AlertRepository alertRepository, RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.alertRepository = alertRepository;
        this.restTemplate = restTemplate;
    }

    // Run every day at midnight (for demo, maybe run every 5 minutes: "0 0/5 * * * ?")
    // Let's use a fixed delay for testing, but typically "0 0 0 * * ?"
    @Scheduled(cron = "0 0 0 * * ?") 
    public void checkBudgets() {
        log.info("Running daily budget alert check");
        Iterable<UserInfo> users = userRepository.findAll();
        
        for (UserInfo user : users) {
            if (user.getMonthlyBudget() == null || user.getMonthlyBudget().compareTo(BigDecimal.ZERO) <= 0) {
                continue; // No budget set
            }

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-User-Id", user.getUserId());
                HttpEntity<String> entity = new HttpEntity<>("parameters", headers);

                // Fetch total from expenseService
                BigDecimal totalSpent = restTemplate.exchange(
                        expenseServiceUrl + "/expense/v1/currentMonthTotal",
                        HttpMethod.GET,
                        entity,
                        BigDecimal.class
                ).getBody();

                if (totalSpent == null) totalSpent = BigDecimal.ZERO;

                BigDecimal ratio = totalSpent.divide(user.getMonthlyBudget(), 2, RoundingMode.HALF_UP);
                
                String alertMessage = null;
                if (ratio.compareTo(BigDecimal.valueOf(1.0)) >= 0) {
                    alertMessage = "You have exceeded your monthly budget of " + user.getDefaultCurrency() + " " + user.getMonthlyBudget() + "!";
                } else if (ratio.compareTo(BigDecimal.valueOf(0.8)) >= 0) {
                    alertMessage = "You have reached " + ratio.multiply(BigDecimal.valueOf(100)).intValue() + "% of your monthly budget.";
                }

                if (alertMessage != null) {
                    // Check if we already alerted this message today to prevent spam (for a real app). 
                    // For now, just save it.
                    Alert alert = Alert.builder()
                            .userId(user.getUserId())
                            .message(alertMessage)
                            .isRead(false)
                            .build();
                    alertRepository.save(alert);
                    log.info("Alert generated for user {}: {}", user.getUserId(), alertMessage);
                }

            } catch (Exception e) {
                log.error("Failed to check budget for user {}", user.getUserId(), e);
            }
        }
    }
}
