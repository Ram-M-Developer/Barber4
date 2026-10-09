package com.barberease.services;

import com.barberease.models.Admin;
import com.barberease.models.AuditLog;
import com.barberease.models.Customer;
import com.barberease.models.WaitingChair;
import com.barberease.repositories.AdminRepository;
import com.barberease.repositories.AuditLogRepository;
import com.barberease.repositories.CustomerRepository;
import com.barberease.repositories.WaitingChairRepository;
import com.barberease.websocket.BarberWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;

@Service
@Transactional
public class WaitingChairService {

    @Autowired
    private WaitingChairRepository waitingChairRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private BarberWebSocketHandler webSocketHandler;

    public List<Map<String, Object>> getAllWaitingChairsEnriched() {
        List<WaitingChair> chairs = waitingChairRepository.findByIsActiveTrueOrderByChairNumberAsc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (WaitingChair wc : chairs) {
            result.add(toMap(wc));
        }
        return result;
    }

    public Map<String, Object> toMap(WaitingChair wc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", wc.getId());
        m.put("chair_number", wc.getChairNumber());
        m.put("name", wc.getName() != null ? wc.getName() : "W" + wc.getChairNumber());
        m.put("status", wc.getStatus());
        m.put("is_active", wc.isActive());
        if (wc.getOccupiedByCustomer() != null) {
            m.put("customer_name", wc.getOccupiedByCustomer().getName());
            m.put("customer_id", wc.getOccupiedByCustomer().getId());
        } else {
            m.put("customer_name", null);
            m.put("customer_id", null);
        }
        m.put("occupied_at", wc.getOccupiedAt() != null ? wc.getOccupiedAt().toString() : null);
        return m;
    }

    /**
     * Admin assigns a customer to a waiting chair.
     */
    public WaitingChair assignCustomer(Long chairId, Long customerId, Long adminId) {
        WaitingChair chair = waitingChairRepository.findById(chairId)
                .orElseThrow(() -> new IllegalArgumentException("Waiting chair not found"));

        if ("occupied".equalsIgnoreCase(chair.getStatus())) {
            throw new IllegalStateException("Waiting chair " + chair.getChairNumber() + " is already occupied.");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        String prevState = chair.getStatus();
        chair.setStatus("occupied");
        chair.setOccupiedByCustomer(customer);
        chair.setOccupiedAt(LocalDateTime.now());
        WaitingChair saved = waitingChairRepository.save(chair);

        auditLog(adminId, "ASSIGN_WAITING_CHAIR", "WaitingChair", chairId, prevState, "occupied",
                "Assigned customer " + customer.getName());

        webSocketHandler.broadcast("waiting-chair-update");
        return saved;
    }

    /**
     * Admin releases a waiting chair.
     */
    public WaitingChair releaseChair(Long chairId, Long adminId, String reason) {
        WaitingChair chair = waitingChairRepository.findById(chairId)
                .orElseThrow(() -> new IllegalArgumentException("Waiting chair not found"));

        String prevState = chair.getStatus();
        String prevCustomer = chair.getOccupiedByCustomer() != null ? chair.getOccupiedByCustomer().getName() : "none";

        chair.setStatus("vacant");
        chair.setOccupiedByCustomer(null);
        chair.setOccupiedAt(null);
        WaitingChair saved = waitingChairRepository.save(chair);

        auditLog(adminId, "RELEASE_WAITING_CHAIR", "WaitingChair", chairId, prevState + " (" + prevCustomer + ")",
                "vacant", reason != null ? reason : "Released by admin");

        webSocketHandler.broadcast("waiting-chair-update");
        return saved;
    }

    /**
     * Releases any waiting chair occupied by a customer when they move to service or leave.
     */
    public void releaseChairForCustomer(Long customerId) {
        if (customerId == null) return;
        waitingChairRepository.findByOccupiedByCustomerId(customerId).ifPresent(chair -> {
            chair.setStatus("vacant");
            chair.setOccupiedByCustomer(null);
            chair.setOccupiedAt(null);
            waitingChairRepository.save(chair);
            webSocketHandler.broadcast("waiting-chair-update");
        });
    }

    public long countVacant() {
        return waitingChairRepository.countByStatus("vacant");
    }

    public long countOccupied() {
        return waitingChairRepository.countByStatus("occupied");
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
            // Audit failures must never block the main operation
            System.err.println("Audit log save failed: " + e.getMessage());
        }
    }
}
