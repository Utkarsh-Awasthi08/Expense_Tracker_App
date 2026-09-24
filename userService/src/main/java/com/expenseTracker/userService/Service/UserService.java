package com.expenseTracker.userService.Service;

import com.expenseTracker.userService.Entities.UpdateProfileRequest;
import com.expenseTracker.userService.Entities.UserInfo;
import com.expenseTracker.userService.Entities.UserInfoDTO;
import com.expenseTracker.userService.Error.UserNotFoundException;
import com.expenseTracker.userService.Repository.UserRepository;
import com.expenseTracker.userService.Validation.UnicodeText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The caller's profile, or {@link UserNotFoundException} while the user.created event has not landed. */
    @Transactional(readOnly = true)
    public UserInfoDTO getProfile(String userId) {
        return UserInfoDTO.from(find(userId));
    }

    /**
     * Partial update of the caller's own row. Only non-null request fields change; the row is looked up by the
     * header-derived user id and is never created here (no row means 404). The row is read with a pessimistic
     * write lock, so concurrent updates of the same user queue up behind each other instead of overwriting each
     * other's fields; {@code @DynamicUpdate} on the entity additionally limits the UPDATE to the changed columns.
     */
    @Transactional
    public UserInfoDTO updateProfile(String userId, UpdateProfileRequest request) {
        UserInfo user = userRepository.findByUserIdForUpdate(userId).orElseThrow(UserNotFoundException::new);
        if (request.getFirstName() != null) {
            user.setFirstName(UnicodeText.strip(request.getFirstName()));
        }
        if (request.getLastName() != null) {
            user.setLastName(UnicodeText.strip(request.getLastName()));
        }
        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getEmail() != null) {
            user.setEmail(UnicodeText.strip(request.getEmail()));
        }
        if (request.getProfilePicture() != null) {
            user.setProfilePicture(UnicodeText.strip(request.getProfilePicture()));
        }
        if (request.getDefaultCurrency() != null) {
            user.setDefaultCurrency(request.getDefaultCurrency().toUpperCase(Locale.ROOT));
        }
        if (request.getTimezone() != null) {
            user.setTimezone(request.getTimezone());
        }
        if (request.getMonthlyBudget() != null) {
            user.setMonthlyBudget(request.getMonthlyBudget());
        }
        return UserInfoDTO.from(userRepository.save(user));
    }

    /**
     * Legacy entry point of the Kafka consumer, kept behaviour-preserving against the new entity: an existing row
     * is left untouched, a missing one is inserted. Rewritten together with the consumer in Stage 4.
     */
    @Transactional
    public UserInfoDTO createOrUpdateUser(UserInfoDTO userInfoDto) {
        UserInfo userInfo = userRepository.findByUserId(userInfoDto.getUserId())
                .orElseGet(() -> userRepository.save(userInfoDto.transformToUserInfo()));
        return UserInfoDTO.from(userInfo);
    }

    private UserInfo find(String userId) {
        return userRepository.findByUserId(userId).orElseThrow(UserNotFoundException::new);
    }
}
