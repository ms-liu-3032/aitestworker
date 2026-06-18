package com.company.aitest.user;

import java.util.List;

import com.company.aitest.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {
    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<List<UserRecord>> list() {
        return ApiResponse.ok(userService.listUsers());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUB_ADMIN')")
    public ApiResponse<UserRecord> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(userService.createUser(
                request.username(),
                request.password(),
                request.displayName(),
                request.roleCode() == null ? "USER" : request.roleCode()
        ));
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String password, String displayName, String roleCode) {
    }
}
