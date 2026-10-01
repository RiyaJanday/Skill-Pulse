package com.skillpulse.admin;

import com.skillpulse.auth.AppUser;
import com.skillpulse.auth.AuthService;
import com.skillpulse.auth.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class AdminUserService {
    private final AuthService auth;
    private final UserRepository users;

    public AdminUserService(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDtos.UserRow> list(String token) {
        AppUser admin = auth.requireAdmin(token);
        List<AppUser> all = users.findAll();
        all.sort(Comparator.comparing(AppUser::getCreatedAt).reversed());
        List<AdminUserDtos.UserRow> result = new ArrayList<AdminUserDtos.UserRow>();
        for (AppUser user : all) {
            result.add(new AdminUserDtos.UserRow(user, user.getId().equals(admin.getId())));
        }
        return result;
    }

    @Transactional
    public AdminUserDtos.UserRow updateRole(String token, Long userId, AdminUserDtos.RoleRequest request) {
        AppUser admin = auth.requireAdmin(token);
        AppUser target = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        AppUser.Role role = parseRole(request == null ? null : request.getRole());

        if (target.getId().equals(admin.getId()) && role != AppUser.Role.ADMIN) {
            throw new IllegalArgumentException("You cannot remove your own administrator access.");
        }
        if (target.getRole() == AppUser.Role.ADMIN && role != AppUser.Role.ADMIN
                && users.countByRole(AppUser.Role.ADMIN) <= 1) {
            throw new IllegalArgumentException("At least one administrator account is required.");
        }

        target.setRole(role);
        users.save(target);
        return new AdminUserDtos.UserRow(target, target.getId().equals(admin.getId()));
    }

    private AppUser.Role parseRole(String value) {
        try {
            return AppUser.Role.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Select a valid user role.");
        }
    }
}
