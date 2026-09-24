package com.expenseTracker.userService.Config;

import com.expenseTracker.userService.Error.PinnedErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Note: there is intentionally no ObjectMapper bean here. A hand-built one would replace Boot's mapper and drop
 * its module and feature configuration; snake_case is applied per DTO with @JsonNaming, never globally.
 */
@Configuration
public class UserConfig {

    @Bean
    public ErrorAttributes errorAttributes() {
        return new PinnedErrorAttributes();
    }
}
