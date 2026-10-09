package com.barberease.controllers;

import com.barberease.models.WaitingChair;
import com.barberease.security.UserPrincipal;
import com.barberease.services.WaitingChairService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only REST controller for physical waiting chairs.
 * These are distinct from service seats (chairs table).
 * All mutating operations require ADMIN role.
 */
@RestController
@RequestMapping("/api/waiting-chairs")
public class WaitingChairController {

    @Autowired
    private WaitingChairService waitingChairService;

    private UserPrincipal requireAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new AccessDeniedException("Authentication required");
        }
        UserPrincipal p = (UserPrincipal) auth.getPrincipal();
        if (!"admin".equalsIgnoreCase(p.getType()) && !"admin".equalsIgnoreCase(p.getRole())) {
            throw new AccessDeniedException("Admin role required");
        }
        return p;
    }

    /**
     * GET /api/waiting-chairs — Admin: full enriched list
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll() {
        requireAdmin();
        List<Map<String, Object>> chairs = waitingChairService.getAllWaitingChairsEnriched();
        long vacant = waitingChairService.countVacant();
        long occupied = waitingChairService.countOccupied();

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", chairs);
        body.put("vacant_count", vacant);
        body.put("occupied_count", occupied);
        return ResponseEntity.ok(body);
    }

    /**
     * POST /api/waiting-chairs/{id}/assign — Admin: assign a customer to this waiting chair
     */
    @PostMapping("/{id}/assign")
    public ResponseEntity<Map<String, Object>> assign(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        UserPrincipal admin = requireAdmin();
        Object custObj = request.get("customer_id");
        if (custObj == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "customer_id is required");
            return ResponseEntity.badRequest().body(err);
        }
        Long customerId = custObj instanceof Number ? ((Number) custObj).longValue() : Long.parseLong(custObj.toString());
        WaitingChair chair = waitingChairService.assignCustomer(id, customerId, admin.getId());

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Customer assigned to waiting chair");
        body.put("data", waitingChairService.toMap(chair));
        return ResponseEntity.ok(body);
    }

    /**
     * POST /api/waiting-chairs/{id}/release — Admin: release a waiting chair
     */
    @PostMapping("/{id}/release")
    public ResponseEntity<Map<String, Object>> release(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> request) {
        UserPrincipal admin = requireAdmin();
        String reason = request != null ? request.get("reason") : null;
        WaitingChair chair = waitingChairService.releaseChair(id, admin.getId(), reason);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Waiting chair released");
        body.put("data", waitingChairService.toMap(chair));
        return ResponseEntity.ok(body);
    }
}
