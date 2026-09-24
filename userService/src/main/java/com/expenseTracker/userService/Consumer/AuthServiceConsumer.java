package com.expenseTracker.userService.Consumer;

import com.expenseTracker.userService.Entities.UserInfo;
import com.expenseTracker.userService.Entities.UserInfoDTO;
import com.expenseTracker.userService.Repository.UserRepository;
import com.expenseTracker.userService.Service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Legacy consumer, kept compiling and behaviour-preserving against the Stage 2 entity (a missing row is inserted,
 * an existing one is left untouched). Stage 4 rewrites it: contract topic and group, ErrorHandlingDeserializer,
 * DefaultErrorHandler + dead-letter topic, and an explicit create-only implementation.
 */
@Service
public class AuthServiceConsumer {

    @Autowired
    private UserService userService;

    @KafkaListener(topics = "${spring.kafka.topic-json.name}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(UserInfoDTO userInfoDTO) {
        try {
            userService.createOrUpdateUser(userInfoDTO);
            System.out.println("User info saved: " + userInfoDTO);
        } catch (Exception e) {
            System.err.println("Error processing event data: " + e.getMessage());
        }
    }
}
