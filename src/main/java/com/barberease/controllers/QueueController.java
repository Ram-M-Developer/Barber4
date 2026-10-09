package com.barberease.controllers;

import com.barberease.models.AuditLog;
import com.barberease.models.Admin;
import com.barberease.models.Queue;
import com.barberease.repositories.AdminRepository;
import com.barberease.repositories.AuditLogRepository;
import com.barberease.security.UserPrincipal;
import com.barberease.services.QueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    @Autowired
    private QueueService queueService;

    @Autowired
    private com.barberease.repositories.AppointmentRepository appointmentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AdminRepository adminRepository;

    // ── Auth helpers ──────────────────────────────────────────────────────────

    private UserPrincipal getAuthenticatedPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return (UserPrincipal) auth.getPrincipal();
    }

    private Long getAuthenticatedCustomerId() {
        return getAuthenticatedPrincipal().getId();
    }

    private String getAuthenticatedUserType() {
        return getAuthenticatedPrincipal().getType();
    }

    /** Enforce admin-only access; returns the principal for audit logging. */
    private UserPrincipal requireAdmin() {
        UserPrincipal p = getAuthenticatedPrincipal();
        if (!"admin".equalsIgnoreCase(p.getType()) && !"admin".equalsIgnoreCase(p.getRole())) {
            throw new AccessDeniedException("Admin role required for this operation");
        }
        return p;
    }

    private Long parseId(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        throw new IllegalArgumentException("Invalid ID format");
    }

    private void auditLog(Long adminId, String action, String entityType, Long entityId,
                          String prevState, String newState, String reason) {
        try {
            AuditLog log = new AuditLog();
            if (adminId != null) {
                adminRepository.findById(adminId).ifPresent(log::setAdmin);
            }
            log.setAction(action);
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setPreviousState(prevState);
            log.setNewState(newState);
            log.setReason(reason);
            auditLogRepository.save(log);
        } catch (Exception e) {
            System.err.println("Audit log save failed: " + e.getMessage());
        }
    }

    // ── Customer: Join queue ──────────────────────────────────────────────────

    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> join(@RequestBody Map<String, Object> request) {
        Long customerId = getAuthenticatedCustomerId();
        Long serviceId = parseId(request.get("service_id"));
        Long chairId = parseId(request.get("chair_id"));

        Queue joined = queueService.addToQueue(customerId, serviceId, chairId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Joined queue successfully");
        body.put("data", joined);
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    // ── Public: Active queue (authenticated) ──────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, Object>> getActive(
            @RequestParam(name = "chair_id", required = false) Long chairId,
            @RequestParam(name = "all", required = false, defaultValue = "false") boolean includeAll) {
        List<Queue> active = queueService.getActiveQueue(chairId, includeAll);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", active);
        return ResponseEntity.ok(body);
    }

    // ── Admin-only: Full waiting list ─────────────────────────────────────────

    @GetMapping("/waiting")
    public ResponseEntity<Map<String, Object>> getWaiting() {
        requireAdmin(); // Admin-only: full queue list with customer names
        List<Queue> waiting = queueService.getWaitingQueue();
        List<Map<String, Object>> list = new java.util.ArrayList<>();

        for (int i = 0; i < waiting.size(); i++) {
            Queue q = waiting.get(i);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("position", i + 1);
            m.put("token_number", q.getTokenNumber());
            m.put("status", q.getStatus());
            String name = q.getCustomer() != null ? q.getCustomer().getName() : "Customer";
            m.put("customer_name", name);
            m.put("customer_id", q.getCustomer() != null ? q.getCustomer().getId() : null);
            m.put("service_name", q.getService() != null ? q.getService().getName() : "Haircut");
            m.put("service_id", q.getService() != null ? q.getService().getId() : null);
            m.put("estimated_wait_minutes", q.getEstimatedWaitMinutes());
            m.put("joined_at", q.getCreatedAt() != null ? q.getCreatedAt().toString() : null);

            String requestedSlot = "Next Available";
            if (q.getTokenNumber() != null) {
                com.barberease.models.Appointment appt = appointmentRepository.findByTokenNumber(q.getTokenNumber()).stream().findFirst().orElse(null);
                if (appt != null && appt.getTimeSlot() != null) {
                    requestedSlot = appt.getTimeSlot();
                }
            }
            m.put("requested_slot", requestedSlot);

            String assignedSeat = null;
            if (q.getChair() != null) {
                assignedSeat = "Seat " + q.getChair().getChairNumber();
            }
            m.put("assigned_seat", assignedSeat);
            m.put("created_at", q.getCreatedAt() != null ? q.getCreatedAt().toString() : null);
            list.add(m);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("count", list.size());
        body.put("data", list);
        return ResponseEntity.ok(body);
    }

    // ── Customer: Own queue status (own data only) ────────────────────────────

    @GetMapping("/my-status")
    public ResponseEntity<Map<String, Object>> getMyStatus() {
        Long customerId = getAuthenticatedCustomerId();
        Queue status = queueService.getCustomerQueueStatus(customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);

        if (status == null) {
            body.put("data", null);
            return ResponseEntity.ok(body);
        }

        // Calculate position among waiting entries
        List<Queue> allWaiting = queueService.getWaitingQueue();
        int position = 1;
        for (int i = 0; i < allWaiting.size(); i++) {
            if (allWaiting.get(i).getId().equals(status.getId())) {
                position = i + 1;
                break;
            }
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", status.getId());
        data.put("token_number", status.getTokenNumber());
        data.put("status", status.getStatus());
        data.put("position", position);
        data.put("estimated_wait_minutes", status.getEstimatedWaitMinutes());
        data.put("service", status.getService() != null ?
            java.util.Map.of("id", status.getService().getId(), "name", status.getService().getName()) : null);
        data.put("created_at", status.getCreatedAt() != null ? status.getCreatedAt().toString() : null);
        // Include assigned seat number if the customer has been called to a service seat
        if (status.getChair() != null) {
            data.put("assigned_seat_number", status.getChair().getChairNumber());
            data.put("assigned_seat_name", "Seat " + status.getChair().getChairNumber());
        } else {
            data.put("assigned_seat_number", null);
            data.put("assigned_seat_name", null);
        }


        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    // ── Customer: Waiting count (aggregate only, no names) ───────────────────

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getWaitingCount() {
        long count = queueService.getWaitingCount();
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("count", count);
        return ResponseEntity.ok(body);
    }

    // ── Admin-only: Call next customer ───────────────────────────────────────

    @PostMapping("/call-next")
    public ResponseEntity<Map<String, Object>> callNext() {
        UserPrincipal admin = requireAdmin();
        Map<String, Object> data = queueService.callNextCustomer();
        if (data == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("success", false);
            body.put("message", "No waiting customers or no chairs currently available");
            return ResponseEntity.badRequest().body(body);
        }

        auditLog(admin.getId(), "CALL_NEXT_CUSTOMER", "Queue", null, "waiting", "called", "Admin called next");

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Called next customer");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    // ── Admin-only: Call specific customer ───────────────────────────────────

    @PostMapping("/{id}/call")
    public ResponseEntity<Map<String, Object>> callCustomer(
            @PathVariable Long id,
            @RequestParam(value = "chair_id", required = false) Long chairId) {
        UserPrincipal admin = requireAdmin();
        Map<String, Object> data = queueService.callQueueEntry(id, chairId);

        auditLog(admin.getId(), "CALL_CUSTOMER", "Queue", id, "waiting", "called", null);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Customer called to station");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    // ── Admin-only: Seat customer (assign to service chair) ──────────────────

    @PostMapping("/{id}/seat")
    public ResponseEntity<Map<String, Object>> seatCustomer(@PathVariable Long id) {
        UserPrincipal admin = requireAdmin();
        Map<String, Object> data = queueService.seatQueueEntry(id);

        auditLog(admin.getId(), "SEAT_CUSTOMER", "Queue", id, "called", "serving", null);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Customer seated and service started");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    // ── Admin-only: Complete queue service ───────────────────────────────────

    @PostMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> completeCustomer(@PathVariable Long id) {
        UserPrincipal admin = requireAdmin();
        Map<String, Object> data = queueService.completeQueueEntry(id);

        auditLog(admin.getId(), "COMPLETE_SERVICE", "Queue", id, "serving", "completed", null);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Service completed");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    // ── Mixed: Cancel queue entry (customer cancels own, admin cancels any) ──

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> requestBody) {
        UserPrincipal principal = getAuthenticatedPrincipal();
        Long customerId = null;
        if ("customer".equals(principal.getType())) {
            customerId = principal.getId();
        }

        String reason = requestBody != null ? requestBody.get("reason") : null;
        Queue cancelled = queueService.cancelQueueEntry(id, customerId);

        if ("admin".equals(principal.getType())) {
            auditLog(principal.getId(), "CANCEL_QUEUE_ENTRY", "Queue", id, "waiting", "cancelled",
                    reason != null ? reason : "Cancelled by admin");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Queue entry cancelled");
        body.put("data", cancelled);
        return ResponseEntity.ok(body);
    }

    // ── Admin-only: Skip customer ─────────────────────────────────────────────

    @PostMapping("/{id}/skip")
    public ResponseEntity<Map<String, Object>> skipCustomer(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> requestBody) {
        UserPrincipal admin = requireAdmin();
        String reason = requestBody != null ? requestBody.get("reason") : null;
        Queue skipped = queueService.skipQueueEntry(id);

        auditLog(admin.getId(), "SKIP_CUSTOMER", "Queue", id, "waiting", "skipped",
                reason != null ? reason : "Skipped by admin");

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Customer skipped");
        body.put("data", skipped);
        return ResponseEntity.ok(body);
    }

    // ── Admin-only: Mark no-show ──────────────────────────────────────────────

    @PostMapping("/{id}/no-show")
    public ResponseEntity<Map<String, Object>> markNoShow(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> requestBody) {
        UserPrincipal admin = requireAdmin();
        String reason = requestBody != null ? requestBody.get("reason") : null;
        Queue noShow = queueService.markNoShow(id);

        auditLog(admin.getId(), "MARK_NO_SHOW", "Queue", id, "waiting", "no_show",
                reason != null ? reason : "No-show marked by admin");

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Customer marked as no-show");
        body.put("data", noShow);
        return ResponseEntity.ok(body);
    }

    // ── Admin-only: Queue stats ───────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        requireAdmin();
        Map<String, Object> stats = queueService.getQueueStats();

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", stats);
        return ResponseEntity.ok(body);
    }
}
