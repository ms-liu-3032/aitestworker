package com.company.aitest.auth;

import com.company.aitest.common.ApiResponse;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.user.UserRecord;
import com.company.aitest.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @GetMapping("/init-status")
    public ApiResponse<InitStatusResponse> initStatus() {
        return ApiResponse.ok(new InitStatusResponse(userService.hasAnyUser()));
    }

    @PostMapping("/init-admin")
    public ApiResponse<LoginResponse> initAdmin(@Valid @RequestBody InitAdminRequest request) {
        UserRecord user = userService.initAdmin(request.username(), request.password(), request.displayName());
        String token = jwtService.issue(new CurrentUser(user.id(), user.username(), user.roleCode()));
        return ApiResponse.ok(new LoginResponse(token, user));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        UserRecord user = userService.login(request.username(), request.password());
        String token = jwtService.issue(new CurrentUser(user.id(), user.username(), user.roleCode()));
        return ApiResponse.ok(new LoginResponse(token, user));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUser> me(Authentication authentication) {
        return ApiResponse.ok((CurrentUser) authentication.getPrincipal());
    }

    public record InitStatusResponse(boolean initialized) {
    }

    public record InitAdminRequest(@NotBlank String username, @NotBlank String password, String displayName) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, UserRecord user) {
    }
}
