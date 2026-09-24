package com.expenseTracker.userService.Service;

import com.expenseTracker.userService.Entities.UserInfo;
import com.expenseTracker.userService.Repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
public class StreakCron {

    @Autowired
    private UserRepository userInfoRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${GATEWAY_URL:http://localhost:8000}")
    private String gatewayUrl;

    // Run every day at 1:30 AM
    @Scheduled(cron = "0 30 1 * * ?")
    public void calculateStreaks() {
        Iterable<UserInfo> users = userInfoRepository.findAll();
        LocalDate yesterday = LocalDate.now().minusDays(1);

        for (UserInfo user : users) {
            if (user.getMonthlyBudget() == null || user.getMonthlyBudget().compareTo(BigDecimal.ZERO) <= 0) {
                continue; // Cannot calculate limit
            }

            BigDecimal dailyLimit = user.getMonthlyBudget().divide(new BigDecimal(30), 2, RoundingMode.HALF_UP);
            BigDecimal spentYesterday = fetchDailyTotal(user.getUserId(), yesterday);

            if (spentYesterday.compareTo(dailyLimit) <= 0) {
                user.setCurrentStreak((user.getCurrentStreak() != null ? user.getCurrentStreak() : 0) + 1);
            } else {
                user.setCurrentStreak(0);
            }
            userInfoRepository.save(user);
        }
    }

    private BigDecimal fetchDailyTotal(String userId, LocalDate date) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", userId);
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            String url = gatewayUrl + "/expense/v1/dailyTotal?date=" + date.toString();
            ResponseEntity<BigDecimal> response = restTemplate.exchange(url, HttpMethod.GET, entity, BigDecimal.class);
            return response.getBody() != null ? response.getBody() : BigDecimal.ZERO;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
