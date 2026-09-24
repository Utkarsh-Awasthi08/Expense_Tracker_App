package com.expenseTracker.userService.Controller;

import com.expenseTracker.userService.Entities.Subscription;
import com.expenseTracker.userService.Repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/subscription/v1")
public class SubscriptionController {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @GetMapping
    public List<Subscription> getSubscriptions(@RequestHeader("X-User-Id") String userId) {
        return subscriptionRepository.findByUserId(userId);
    }

    @PostMapping
    public ResponseEntity<Subscription> createSubscription(@RequestHeader("X-User-Id") String userId, @RequestBody Subscription req) {
        req.setUserId(userId);
        return ResponseEntity.ok(subscriptionRepository.save(req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubscription(@RequestHeader("X-User-Id") String userId, @PathVariable Long id) {
        return subscriptionRepository.findById(id).map(sub -> {
            if (sub.getUserId().equals(userId)) {
                subscriptionRepository.delete(sub);
                return ResponseEntity.ok().<Void>build();
            }
            return ResponseEntity.status(403).<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
