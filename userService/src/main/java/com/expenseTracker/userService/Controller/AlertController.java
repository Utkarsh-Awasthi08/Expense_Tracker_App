package com.expenseTracker.userService.Controller;

import com.expenseTracker.userService.Entities.Alert;
import com.expenseTracker.userService.Identity.UserIdFilter;
import com.expenseTracker.userService.Repository.AlertRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/alert/v1")
public class AlertController {

    private final AlertRepository alertRepository;

    public AlertController(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @GetMapping
    public List<Alert> getAlerts(@RequestAttribute(UserIdFilter.USER_ID_ATTRIBUTE) String userId) {
        return alertRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @PostMapping("/{id}/read")
    public void markAsRead(@RequestAttribute(UserIdFilter.USER_ID_ATTRIBUTE) String userId, @PathVariable Long id) {
        alertRepository.findById(id).ifPresent(alert -> {
            if (alert.getUserId().equals(userId)) {
                alert.setRead(true);
                alertRepository.save(alert);
            }
        });
    }
}
