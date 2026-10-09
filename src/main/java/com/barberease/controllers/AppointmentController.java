package com.barberease.controllers;

import com.barberease.models.Appointment;
import com.barberease.security.UserPrincipal;
import com.barberease.services.AppointmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    @Autowired
    private AppointmentService appointmentService;

    private Long getAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getId();
    }

    private String getAuthenticatedUserType() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getType();
    }

    private Long parseId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        throw new IllegalArgumentException("Invalid ID format");
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        Long customerId = getAuthenticatedCustomerId();

        Long serviceId = parseId(request.get("service_id"));
        Long chairId = parseId(request.get("chair_id"));
        
        String dateStr = (String) request.get("appointment_date");
        LocalDate appointmentDate = LocalDate.parse(dateStr);

        String timeSlot = (String) request.get("time_slot");
        String notes = (String) request.get("notes");
        String paymentMethod = (String) request.get("payment_method");

        Appointment created = appointmentService.createAppointment(
                customerId, serviceId, chairId, appointmentDate, timeSlot, notes, paymentMethod);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Appointment confirmed");
        body.put("data", created);
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    @GetMapping("/my")
    public ResponseEntity<Map<String, Object>> getMy() {
        Long customerId = getAuthenticatedCustomerId();
        List<Appointment> appts = appointmentService.getCustomerAppointments(customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", appts);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/slots")
    public ResponseEntity<Map<String, Object>> getSlots(
            @RequestParam(name = "chairId", required = false) Long chairId,
            @RequestParam(name = "date", required = false) String dateStr) {
        
        LocalDate date = (dateStr != null && !dateStr.isEmpty()) ? LocalDate.parse(dateStr) : LocalDate.now();
        List<String> bookedSlots = appointmentService.getBookedSlots(chairId, date);

        Map<String, Object> data = new HashMap<>();
        data.put("chairId", chairId);
        data.put("date", date.toString());
        data.put("bookedSlots", bookedSlots);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private Long getOptionalAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && (auth.getPrincipal() instanceof UserPrincipal)) {
            return ((UserPrincipal) auth.getPrincipal()).getId();
        }
        return null;
    }

    private boolean isAdminCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) return false;
        UserPrincipal p = (UserPrincipal) auth.getPrincipal();
        return "admin".equalsIgnoreCase(p.getType()) || "admin".equalsIgnoreCase(p.getRole());
    }

    @GetMapping("/slots/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedSlots(
            @RequestParam(name = "date", required = false) String dateStr) {
        LocalDate date = (dateStr != null && !dateStr.isEmpty()) ? LocalDate.parse(dateStr) : LocalDate.now();
        Long customerId = getOptionalAuthenticatedCustomerId();
        boolean isAdmin = isAdminCaller();

        List<Map<String, Object>> slots = appointmentService.getDetailedSlots(date, customerId);

        // For non-admin callers: strip other customers' names from bookedCustomers
        if (!isAdmin) {
            for (Map<String, Object> slot : slots) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> bookedCustomers = (List<Map<String, Object>>) slot.get("bookedCustomers");
                if (bookedCustomers != null) {
                    for (Map<String, Object> customer : bookedCustomers) {
                        // Only preserve name if it's the current customer's booking
                        Object cId = customer.get("customerId");
                        boolean isOwn = customerId != null && cId != null && customerId.toString().equals(cId.toString());
                        if (!isOwn) {
                            customer.put("customerName", "Customer"); // anonymise
                        }
                    }
                }
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("date", date.toString());
        body.put("data", slots);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/book-slot")
    public ResponseEntity<Map<String, Object>> bookSlot(@RequestBody Map<String, Object> request) {
        Long customerId = getAuthenticatedCustomerId();
        Object serviceObj = request.get("service_id") != null ? request.get("service_id") : request.get("serviceId");
        Long serviceId = parseId(serviceObj);

        Object dateObj = request.get("appointment_date") != null ? request.get("appointment_date") : request.get("appointmentDate");
        String dateStr = dateObj != null ? dateObj.toString() : null;
        LocalDate date = (dateStr != null && !dateStr.isEmpty()) ? LocalDate.parse(dateStr) : LocalDate.now();

        Object slotObj = request.get("time_slot") != null ? request.get("time_slot") : request.get("timeSlot");
        String timeSlot = slotObj != null ? slotObj.toString() : null;

        Object notesObj = request.get("notes");
        String notes = notesObj != null ? notesObj.toString() : null;

        Object payObj = request.get("payment_method") != null ? request.get("payment_method") : request.get("paymentMethod");
        String paymentMethod = payObj != null ? payObj.toString() : null;

        Appointment booked = appointmentService.bookSlot(customerId, serviceId, date, timeSlot, notes, paymentMethod);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Time slot " + timeSlot + " booked successfully!");
        body.put("data", booked);
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String status,
            @RequestParam(name = "customer_id", required = false) Long customerId) {
        
        LocalDate localDate = date != null ? LocalDate.parse(date) : null;
        List<Appointment> appts = appointmentService.getAllAppointments(localDate, status, customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", appts);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        Long customerId = null;
        if ("customer".equals(getAuthenticatedUserType())) {
            customerId = getAuthenticatedCustomerId();
        }

        Appointment cancelled = appointmentService.cancelAppointment(id, customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Appointment cancelled successfully");
        body.put("data", cancelled);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable Long id) {
        Appointment completed = appointmentService.completeAppointment(id);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Appointment completed");
        body.put("data", completed);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable Long id) {
        Appointment started = appointmentService.startAppointment(id);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Appointment started");
        body.put("data", started);
        return ResponseEntity.ok(body);
    }
}
