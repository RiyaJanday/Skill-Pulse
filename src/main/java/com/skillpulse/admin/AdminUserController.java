package com.skillpulse.admin;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class AdminUserController {
    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/users")
    public List<AdminUserDtos.UserRow> users(@RequestParam("token") String token) {
        return service.list(token);
    }

    @PutMapping("/api/admin/users/{id}/role")
    public AdminUserDtos.UserRow updateRole(@PathVariable("id") Long id,
                                             @RequestParam("token") String token,
                                             @RequestBody AdminUserDtos.RoleRequest request) {
        return service.updateRole(token, id, request);
    }

}
