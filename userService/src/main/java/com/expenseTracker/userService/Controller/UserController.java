package com.expenseTracker.userService.Controller;

import com.expenseTracker.userService.Entities.UpdateProfileRequest;
import com.expenseTracker.userService.Entities.UserInfoDTO;
import com.expenseTracker.userService.Identity.UserIdFilter;
import com.expenseTracker.userService.Service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own profile. The user id is the one {@link UserIdFilter} validated from the X-User-Id header;
 * no endpoint accepts a user id from the body or the path (the old createUpdate/getUser did, an IDOR).
 */
@RestController
@RequestMapping("/user/v1/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserInfoDTO getMe(@RequestAttribute(UserIdFilter.USER_ID_ATTRIBUTE) String userId) {
        return userService.getProfile(userId);
    }

    @PutMapping
    public UserInfoDTO updateMe(@RequestAttribute(UserIdFilter.USER_ID_ATTRIBUTE) String userId,
                                @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(userId, request);
    }
}
