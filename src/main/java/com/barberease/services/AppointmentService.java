package com.barberease.services;

import com.barberease.models.*;
import com.barberease.models.Queue;
import com.barberease.repositories.*;
import com.barberease.websocket.BarberWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class AppointmentService {

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ChairRepository chairRepository;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private QueueService queueService;

    @Autowired
    private BarberWebSocketHandler webSocketHandler;

    public synchronized String generateTokenNumber(LocalDate date) {
        Optional<Token> lastToken = tokenRepository.findFirstByTokenDateOrderByIdDesc(date);
        int nextNumber = 1;

        if (lastToken.isPresent() && lastToken.get().getTokenNumber() != null) {
            String lastNumStr = lastToken.get().getTokenNumber();
            if (lastNumStr.startsWith("T")) {
                try {
                    nextNumber = Integer.parseInt(lastNumStr.substring(1)) + 1;
                } catch (NumberFormatException e) {
                    // Ignore and use 1
                }
            }
        }

        return String.format("T%03d", nextNumber);
    }

    public Appointment createAppointment(Long customerId, Long serviceId, Long chairId, 
                                         LocalDate appointmentDate, String timeSlot, 
                                         String notes, String paymentMethod) {
        
        com.barberease.models.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Service not found or inactive"));

        if (!service.isActive()) {
            throw new IllegalArgumentException("Service not found or inactive");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        BigDecimal price = service.getPrice();
        boolean isWalletPayment = paymentMethod == null || "redeem".equals(paymentMethod);

        if (isWalletPayment) {
            BigDecimal balance = customer.getWalletBalance();
            if (balance.compareTo(price) < 0) {
                throw new IllegalStateException(String.format(
                        "Insufficient wallet balance. Service costs $%.2f but your wallet has $%.2f. Please top up!",
                        price, balance));
            }
            customer.setWalletBalance(balance.subtract(price));
            customerRepository.save(customer);
        }

        Chair chair = chairRepository.findById(chairId)
                .orElseThrow(() -> new IllegalArgumentException("Chair not found. Please select a valid chair."));

        if (!chair.isActive() || "maintenance".equals(chair.getStatus())) {
            throw new IllegalStateException("Chair station is currently out of service. Please select another chair.");
        }

        // Check if the chair has an overlapping appointment for THIS specific time slot and duration
        List<String> activeStatuses = Arrays.asList("pending", "confirmed", "in_progress");
        int newStartMin = parseTimeToMinutes(timeSlot);
        int newDuration = (service != null && service.getDurationMinutes() > 0) ? service.getDurationMinutes() : 30;
        int newEndMin = newStartMin + newDuration;

        List<Appointment> chairAppts = appointmentRepository.findByChairId(chairId);
        for (Appointment exist : chairAppts) {
            if (exist.getAppointmentDate() != null && exist.getAppointmentDate().equals(appointmentDate)
                    && activeStatuses.contains(exist.getStatus())) {
                int existStartMin = parseTimeToMinutes(exist.getTimeSlot());
                if (existStartMin >= 0) {
                    int existDuration = (exist.getService() != null && exist.getService().getDurationMinutes() > 0)
                            ? exist.getService().getDurationMinutes() : 30;
                    int existEndMin = existStartMin + existDuration;

                    // Overlap check: max(newStartMin, existStartMin) < min(newEndMin, existEndMin)
                    if (Math.max(newStartMin, existStartMin) < Math.min(newEndMin, existEndMin)) {
                        String existStartStr = formatMinutesToTime(existStartMin);
                        String existEndStr = formatMinutesToTime(existEndMin);
                        throw new IllegalStateException(String.format(
                                "Time slot %s (%d min) conflicts with an existing appointment (%s to %s) on %s. The next booking is available after %s.",
                                timeSlot, newDuration, existStartStr, existEndStr, chair.getName(), existEndStr));
                    }
                }
            }
        }

        // Check if customer already has an appointment booked for the exact same time slot on this date
        boolean customerAlreadyBooked = appointmentRepository.existsByCustomerIdAndAppointmentDateAndTimeSlotAndStatusIn(
                customerId, appointmentDate, timeSlot, activeStatuses);

        if (customerAlreadyBooked) {
            throw new IllegalStateException("You already have an appointment booked for " + timeSlot + " on this date.");
        }

        String tokenNumber = generateTokenNumber(appointmentDate);

        Appointment appointment = new Appointment();
        appointment.setCustomer(customer);
        appointment.setService(service);
        appointment.setChair(chair);
        appointment.setTokenNumber(tokenNumber);
        appointment.setAppointmentDate(appointmentDate);
        appointment.setTimeSlot(timeSlot);
        appointment.setStatus("confirmed");
        appointment.setNotes(notes);
        Appointment saved = appointmentRepository.save(appointment);

        // Clear temporary checkout hold so other customers can book remaining time slots on this chair
        chair.setStatus("available");
        chair.setReservedByCustomer(null);
        chair.setReservedAt(null);
        chairRepository.save(chair);

        // Create token record
        Token token = new Token();
        token.setTokenNumber(tokenNumber);
        token.setCustomer(customer);
        token.setAppointment(saved);
        token.setType("appointment");
        token.setTokenDate(appointmentDate);
        token.setStatus("active");
        tokenRepository.save(token);

        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("appointment-update");

        return saved;
    }

    public Appointment getAppointmentById(Long id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));
    }

    public List<Appointment> getCustomerAppointments(Long customerId) {
        return appointmentRepository.findByCustomerIdOrderByAppointmentDateDescCreatedAtDesc(customerId);
    }

    public List<Appointment> getAllAppointments(LocalDate date, String status, Long customerId) {
        // Find all and filter in memory or via customized repository calls (for simplicity, do a custom filter or query)
        List<Appointment> all = appointmentRepository.findAll();
        List<Appointment> filtered = new ArrayList<>();
        
        for (Appointment a : all) {
            if (date != null && !a.getAppointmentDate().equals(date)) continue;
            if (status != null && !a.getStatus().equals(status)) continue;
            if (customerId != null && !a.getCustomer().getId().equals(customerId)) continue;
            filtered.add(a);
        }
        
        // Sort: Date DESC, TimeSlot ASC
        filtered.sort((a, b) -> {
            int dateCompare = b.getAppointmentDate().compareTo(a.getAppointmentDate());
            if (dateCompare != 0) return dateCompare;
            return a.getTimeSlot().compareTo(b.getTimeSlot());
        });
        
        return filtered;
    }

    public List<Appointment> getTodayAppointments() {
        return appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(LocalDate.now());
    }

    public static int parseTimeToMinutes(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty()) {
            return -1;
        }
        try {
            String clean = timeStr.trim().toUpperCase();
            boolean isPm = clean.contains("PM");
            clean = clean.replace("AM", "").replace("PM", "").trim();
            String[] parts = clean.split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int min = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (isPm && hour != 12) {
                hour += 12;
            } else if (!isPm && hour == 12) {
                hour = 0;
            }
            return hour * 60 + min;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String formatMinutesToTime(int totalMinutes) {
        int hour = totalMinutes / 60;
        int min = totalMinutes % 60;
        int displayHour = hour % 12 == 0 ? 12 : hour % 12;
        String period = hour < 12 ? "AM" : "PM";
        return String.format("%d:%02d %s", displayHour, min, period);
    }

    public List<String> getBookedSlots(Long chairId, LocalDate date) {
        List<Appointment> appts;
        if (chairId != null) {
            appts = appointmentRepository.findByChairId(chairId);
        } else {
            appts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);
        }
        Set<String> booked = new LinkedHashSet<>();
        List<String> activeStatuses = Arrays.asList("pending", "confirmed", "in_progress");

        for (Appointment a : appts) {
            if (a.getAppointmentDate() != null && a.getAppointmentDate().equals(date) && activeStatuses.contains(a.getStatus())) {
                if (a.getTimeSlot() != null) {
                    int startMin = parseTimeToMinutes(a.getTimeSlot());
                    if (startMin >= 0) {
                        int duration = 30;
                        if (a.getService() != null && a.getService().getDurationMinutes() > 0) {
                            duration = a.getService().getDurationMinutes();
                        }
                        int endMin = startMin + duration;

                        // Add all standard 30-minute intervals that overlap with [startMin, endMin)
                        // Standard salon hours: 8:00 AM (480 min) to 10:00 PM (1320 min)
                        for (int t = 480; t < 1320; t += 30) {
                            if (t < endMin && (t + 30) > startMin) {
                                booked.add(formatMinutesToTime(t));
                            }
                        }
                    } else {
                        booked.add(a.getTimeSlot());
                    }
                }
            }
        }
        return new ArrayList<>(booked);
    }

    public static final List<String> STANDARD_TIME_SLOTS = Arrays.asList(
            "09:00 AM - 10:00 AM",
            "10:00 AM - 11:00 AM",
            "11:00 AM - 12:00 PM",
            "12:00 PM - 01:00 PM",
            "01:00 PM - 02:00 PM",
            "02:00 PM - 03:00 PM",
            "03:00 PM - 04:00 PM",
            "04:00 PM - 05:00 PM",
            "05:00 PM - 06:00 PM",
            "06:00 PM - 07:00 PM",
            "07:00 PM - 08:00 PM",
            "08:00 PM - 09:00 PM",
            "09:00 PM - 10:00 PM"
    );

    public List<String> generateDynamicSlots(LocalDate date) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int openHour = 9;   // 09:00 AM
        int closeHour = 22; // 10:00 PM

        List<String> slots = new ArrayList<>();
        if (date.isBefore(today)) {
            return slots;
        }

        int startHour = openHour;
        if (date.isEqual(today)) {
            if (now.getHour() >= openHour) {
                if (now.getMinute() == 0) {
                    startHour = now.getHour();
                } else {
                    startHour = now.getHour() + 1;
                }
            }
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("hh:mm a");
        for (int h = startHour; h < closeHour; h++) {
            LocalTime start = LocalTime.of(h, 0);
            LocalTime end = start.plusMinutes(60);
            String slotStr = start.format(fmt) + " - " + end.format(fmt);
            slots.add(slotStr);
        }

        return slots;
    }

    public static boolean slotMatches(String apptSlot, String standardSlot) {
        if (apptSlot == null || standardSlot == null) return false;
        String a = apptSlot.trim().toUpperCase();
        String s = standardSlot.trim().toUpperCase();
        if (a.equals(s)) return true;
        if (s.startsWith(a)) return true;
        if (a.startsWith(s)) return true;
        String aStart = a.split("-")[0].trim();
        String sStart = s.split("-")[0].trim();
        return aStart.equals(sStart);
    }

    public List<Map<String, Object>> getDetailedSlots(LocalDate date, Long currentCustomerId) {
        List<Appointment> appts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);
        List<String> activeStatuses = Arrays.asList("pending", "confirmed", "in_progress");

        List<Map<String, Object>> result = new ArrayList<>();
        int totalCapacity = 2; // Exactly 2 seats

        List<String> slotsToDisplay = generateDynamicSlots(date);
        if (slotsToDisplay.isEmpty() && date.isAfter(LocalDate.now())) {
            slotsToDisplay = STANDARD_TIME_SLOTS;
        }

        for (String slot : slotsToDisplay) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timeSlot", slot);
            map.put("capacity", totalCapacity);

            List<Appointment> slotAppts = new ArrayList<>();
            for (Appointment a : appts) {
                if (activeStatuses.contains(a.getStatus()) && slotMatches(a.getTimeSlot(), slot)) {
                    slotAppts.add(a);
                }
            }

            int assignedCount = slotAppts.size();
            int availableCount = Math.max(0, totalCapacity - assignedCount);
            boolean isFull = assignedCount >= totalCapacity;

            map.put("assignedCount", assignedCount);
            map.put("availableCount", availableCount);
            map.put("status", isFull ? "full" : "available");
            map.put("isFull", isFull);

            List<Map<String, Object>> bookedList = new ArrayList<>();
            boolean isCurrent = false;
            for (Appointment sa : slotAppts) {
                Map<String, Object> b = new HashMap<>();
                String customerName = sa.getCustomer() != null ? sa.getCustomer().getName() : "Customer";
                b.put("customerName", customerName);
                b.put("customerId", sa.getCustomer() != null ? sa.getCustomer().getId() : null);
                b.put("appointmentId", sa.getId());
                b.put("tokenNumber", sa.getTokenNumber());
                if (sa.getChair() != null) {
                    b.put("chairNumber", sa.getChair().getChairNumber());
                    b.put("chairName", sa.getChair().getName());
                }
                bookedList.add(b);
                if (currentCustomerId != null && sa.getCustomer() != null
                        && currentCustomerId.equals(sa.getCustomer().getId())) {
                    isCurrent = true;
                }
            }
            map.put("bookedCustomers", bookedList);
            map.put("isCurrentCustomer", isCurrent);
            result.add(map);
        }
        return result;
    }

    private static final Object BOOKING_LOCK = new Object();

    public Appointment bookSlot(Long customerId, Long serviceId, LocalDate date, String timeSlot,
                                String notes, String paymentMethod) {
        synchronized (BOOKING_LOCK) {
            List<String> activeStatuses = Arrays.asList("pending", "confirmed", "in_progress");

            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

            // 1. Check if customer is currently physically occupying an active seat TODAY
            // (Only block if they have a physical seat now, not future bookings)
            boolean isSeated = chairRepository.findAll().stream()
                    .anyMatch(c -> c.getReservedByCustomer() != null && c.getReservedByCustomer().getId().equals(customerId));
            if (isSeated && date.equals(LocalDate.now())) {
                throw new IllegalStateException("You are already occupying an active service seat.");
            }

            // 2. Check if customer is already waiting in the digital queue today
            Optional<com.barberease.models.Queue> existingQueue = queueRepository.findByCustomerIdAndStatusIn(customerId, Arrays.asList("waiting", "called"));
            if (existingQueue.isPresent() && existingQueue.get().getCreatedAt() != null 
                    && existingQueue.get().getCreatedAt().isAfter(LocalDate.now().atStartOfDay())) {
                throw new IllegalStateException("You are already in the waiting queue.");
            }

            List<Appointment> allDateAppts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);

            // 3. Check if customer already has a booking on this date
            for (Appointment a : allDateAppts) {
                if (activeStatuses.contains(a.getStatus()) && a.getCustomer() != null && a.getCustomer().getId().equals(customerId)) {
                    if (slotMatches(a.getTimeSlot(), timeSlot)) {
                        throw new IllegalStateException("You already have a booking for " + timeSlot + " on this date.");
                    }
                }
            }

            // Find existing bookings for this specific slot
            List<Appointment> existingForSlot = new ArrayList<>();
            for (Appointment a : allDateAppts) {
                if (activeStatuses.contains(a.getStatus()) && slotMatches(a.getTimeSlot(), timeSlot)) {
                    existingForSlot.add(a);
                }
            }

            com.barberease.models.Service service = null;
            if (serviceId != null) {
                service = serviceRepository.findById(serviceId).orElse(null);
            }
            if (service == null) {
                service = serviceRepository.findAll().stream()
                        .filter(com.barberease.models.Service::isActive)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No active services available"));
            }

            // Capacity Check: Total seats = 2
            // If already 2 bookings, slot is full -> Enter FIFO Waiting Queue
            if (existingForSlot.size() >= 2) {
                Queue queued = queueService.addToQueue(customerId, service.getId());

                String tokenNumber = queued.getTokenNumber();
                Appointment queueAppt = new Appointment();
                queueAppt.setCustomer(customer);
                queueAppt.setService(service);
                queueAppt.setChair(null);
                queueAppt.setTokenNumber(tokenNumber);
                queueAppt.setAppointmentDate(date);
                queueAppt.setTimeSlot(timeSlot);
                queueAppt.setStatus("pending");
                queueAppt.setNotes(notes != null ? notes + " (Queued: Slot full)" : "(Queued: Slot full)");
                Appointment saved = appointmentRepository.save(queueAppt);

                webSocketHandler.broadcast("slot-update");
                webSocketHandler.broadcast("queue-update");
                webSocketHandler.broadcast("appointment-update");
                return saved;
            }

            boolean isWalletPayment = "wallet".equalsIgnoreCase(paymentMethod) || "redeem".equalsIgnoreCase(paymentMethod);
            if (isWalletPayment) {
                BigDecimal price = service.getPrice() != null ? service.getPrice() : BigDecimal.ZERO;
                BigDecimal balance = customer.getWalletBalance() != null ? customer.getWalletBalance() : BigDecimal.ZERO;
                if (balance.compareTo(price) < 0) {
                    throw new IllegalStateException(String.format(
                            "Insufficient wallet balance. Service costs $%.2f but your wallet has $%.2f.", price, balance));
                }
                customer.setWalletBalance(balance.subtract(price));
                customerRepository.save(customer);
            }

            // Capacity < 2: Determine available seat (Seat 1 or Seat 2)
            Set<Integer> takenChairs = new HashSet<>();
            for (Appointment a : existingForSlot) {
                if (a.getChair() != null) {
                    takenChairs.add(a.getChair().getChairNumber());
                }
            }

            Chair assignedSeat = null;
            if (!takenChairs.contains(1)) {
                assignedSeat = chairRepository.findByChairNumber(1).orElse(null);
            } else if (!takenChairs.contains(2)) {
                assignedSeat = chairRepository.findByChairNumber(2).orElse(null);
            }
            if (assignedSeat == null) {
                assignedSeat = chairRepository.findByStatusAndIsActiveTrueOrderByChairNumberAsc("available")
                        .stream().findFirst().orElse(null);
            }

            if (assignedSeat != null && date.equals(LocalDate.now())) {
                assignedSeat.setStatus("occupied");
                assignedSeat.setReservedByCustomer(customer);
                assignedSeat.setReservedAt(LocalDateTime.now());
                chairRepository.save(assignedSeat);
            }

            String tokenNumber = generateTokenNumber(date);

            Appointment appointment = new Appointment();
            appointment.setCustomer(customer);
            appointment.setService(service);
            appointment.setChair(assignedSeat);
            appointment.setTokenNumber(tokenNumber);
            appointment.setAppointmentDate(date);
            appointment.setTimeSlot(timeSlot);
            appointment.setStatus("confirmed");
            appointment.setNotes(notes);
            Appointment saved = appointmentRepository.save(appointment);

            Token token = new Token();
            token.setTokenNumber(tokenNumber);
            token.setCustomer(customer);
            token.setAppointment(saved);
            token.setType("appointment");
            token.setTokenDate(date);
            token.setStatus("active");
            tokenRepository.save(token);

            webSocketHandler.broadcast("slot-update");
            webSocketHandler.broadcast("chair-update");
            webSocketHandler.broadcast("queue-update");
            webSocketHandler.broadcast("appointment-update");

            return saved;
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public Chair completeSeatService(Long chairId) {
        synchronized (BOOKING_LOCK) {
            Chair chair = chairRepository.findById(chairId)
                    .orElseThrow(() -> new IllegalArgumentException("Seat not found"));

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

            // 1. Mark active appointment on this chair as completed (today's only)
            List<Appointment> activeAppts = appointmentRepository.findByChairId(chairId);
            for (Appointment a : activeAppts) {
                // Only process today's active records to avoid completing historical appointments
                if (("in_progress".equalsIgnoreCase(a.getStatus()) || "confirmed".equalsIgnoreCase(a.getStatus()))
                        && a.getAppointmentDate() != null && a.getAppointmentDate().equals(LocalDate.now())) {
                    a.setStatus("completed");
                    a.setCompletedAt(now);
                    appointmentRepository.save(a);

                    if (a.getTokenNumber() != null) {
                        tokenRepository.findByTokenNumber(a.getTokenNumber()).forEach(t -> {
                            t.setStatus("used");
                            tokenRepository.save(t);
                        });
                    }
                }
            }

            // 2. Mark active queue entry assigned to this chair as completed
            List<Queue> servingQueues = queueRepository.findByChairIdAndStatusInAndCreatedAtAfterOrderByPositionAsc(
                    chairId, Arrays.asList("called", "serving"), startOfDay);
            for (Queue q : servingQueues) {
                q.setStatus("completed");
                q.setCompletedAt(now);
                queueRepository.save(q);
            }

            // Also complete any other active queue entries for the customer who was seated here
            if (chair.getReservedByCustomer() != null) {
                Long prevCustId = chair.getReservedByCustomer().getId();
                List<Queue> prevCustQueues = queueRepository.findAllByCustomerIdAndStatusIn(
                        prevCustId, Arrays.asList("waiting", "called", "serving"));
                for (Queue q : prevCustQueues) {
                    q.setStatus("completed");
                    q.setCompletedAt(now);
                    queueRepository.save(q);
                }
            }

            // 3. Free the assigned seat in database
            chair.setStatus("available");
            chair.setReservedByCustomer(null);
            chair.setReservedAt(null);
            Chair savedChair = chairRepository.save(chair);

            // 4. Check waiting queue: strictly find first ELIGIBLE customer from FIFO queue
            List<Queue> waitingList = queueRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtAsc("waiting", startOfDay);
            if (waitingList.isEmpty()) {
                waitingList = queueRepository.findByStatusOrderByCreatedAtAsc("waiting");
            }

            Queue eligibleCandidate = null;
            for (Queue candidate : waitingList) {
                if (candidate.getCustomer() == null) {
                    candidate.setStatus("cancelled");
                    candidate.setCompletedAt(now);
                    queueRepository.save(candidate);
                    continue;
                }

                // Skip already-finished entries (defensive check against stale status)
                String cs = candidate.getStatus().toLowerCase();
                if (cs.equals("cancelled") || cs.equals("completed") || cs.equals("skipped") || cs.equals("no_show") || cs.equals("interrupted") || cs.equals("serving") || cs.equals("called")) {
                    continue;
                }

                Long custId = candidate.getCustomer().getId();

                // Validate: Is customer already occupying ANY seat right now?
                boolean alreadySeated = chairRepository.findAll().stream()
                        .anyMatch(c -> c.getReservedByCustomer() != null && custId.equals(c.getReservedByCustomer().getId()));
                if (alreadySeated) {
                    // Customer somehow already seated — complete their queue entry
                    candidate.setStatus("completed");
                    candidate.setCompletedAt(now);
                    queueRepository.save(candidate);
                    continue;
                }

                eligibleCandidate = candidate;
                break;
            }

            if (eligibleCandidate != null) {
                // Assign that eligible customer to the freed seat
                eligibleCandidate.setStatus("serving");
                eligibleCandidate.setChair(savedChair);
                eligibleCandidate.setServedAt(now);
                queueRepository.save(eligibleCandidate);

                savedChair.setStatus("occupied");
                savedChair.setReservedByCustomer(eligibleCandidate.getCustomer());
                savedChair.setReservedAt(now);
                chairRepository.save(savedChair);

                // Create or update in-progress appointment for this customer
                List<Appointment> custAppts = appointmentRepository.findByCustomerIdOrderByAppointmentDateDescCreatedAtDesc(eligibleCandidate.getCustomer().getId());
                Appointment activeAppt = null;
                for (Appointment a : custAppts) {
                    if (("pending".equalsIgnoreCase(a.getStatus()) || "confirmed".equalsIgnoreCase(a.getStatus()))
                            && (a.getAppointmentDate() == null || a.getAppointmentDate().equals(LocalDate.now()))) {
                        activeAppt = a;
                        break;
                    }
                }

                if (activeAppt != null) {
                    activeAppt.setChair(savedChair);
                    activeAppt.setStatus("in_progress");
                    activeAppt.setStartedAt(now);
                    appointmentRepository.save(activeAppt);
                } else {
                    Appointment nextAppt = new Appointment();
                    nextAppt.setCustomer(eligibleCandidate.getCustomer());
                    nextAppt.setService(eligibleCandidate.getService());
                    nextAppt.setChair(savedChair);
                    nextAppt.setTokenNumber(eligibleCandidate.getTokenNumber());
                    nextAppt.setAppointmentDate(LocalDate.now());
                    String timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
                    nextAppt.setTimeSlot(timeStr);
                    nextAppt.setStatus("in_progress");
                    nextAppt.setStartedAt(now);
                    appointmentRepository.save(nextAppt);
                }

                if (queueService != null) {
                    queueService.recalculatePositions();
                }
            } else {
                if (queueService != null) {
                    queueService.recalculatePositions();
                }
            }

            webSocketHandler.broadcast("slot-update");
            webSocketHandler.broadcast("chair-update");
            webSocketHandler.broadcast("queue-update");
            webSocketHandler.broadcast("appointment-update");

            return savedChair;
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public Chair forceFreeSeat(Long chairId, String reason) {
        synchronized (BOOKING_LOCK) {
            Chair chair = chairRepository.findById(chairId)
                    .orElseThrow(() -> new IllegalArgumentException("Seat not found"));

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startOfDay = LocalDate.now().atStartOfDay();

            // 1. Mark active appointment on this chair as interrupted (today's only)
            List<Appointment> activeAppts = appointmentRepository.findByChairId(chairId);
            for (Appointment a : activeAppts) {
                // Only process today's active records to avoid corrupting historical data
                if (("in_progress".equalsIgnoreCase(a.getStatus()) || "confirmed".equalsIgnoreCase(a.getStatus()))
                        && a.getAppointmentDate() != null && a.getAppointmentDate().equals(LocalDate.now())) {
                    a.setStatus("interrupted");
                    a.setInterruptedAt(now);
                    a.setInterruptionReason(reason);
                    appointmentRepository.save(a);

                    if (a.getTokenNumber() != null) {
                        tokenRepository.findByTokenNumber(a.getTokenNumber()).forEach(t -> {
                            t.setStatus("interrupted");
                            tokenRepository.save(t);
                        });
                    }
                }
            }

            // 2. Mark active queue entry assigned to this chair as interrupted
            List<Queue> servingQueues = queueRepository.findByChairIdAndStatusInAndCreatedAtAfterOrderByPositionAsc(
                    chairId, Arrays.asList("called", "serving"), startOfDay);
            for (Queue q : servingQueues) {
                q.setStatus("interrupted");
                q.setCompletedAt(now);
                queueRepository.save(q);
            }

            if (chair.getReservedByCustomer() != null) {
                Long prevCustId = chair.getReservedByCustomer().getId();
                List<Queue> prevCustQueues = queueRepository.findAllByCustomerIdAndStatusIn(
                        prevCustId, Arrays.asList("waiting", "called", "serving"));
                for (Queue q : prevCustQueues) {
                    q.setStatus("interrupted");
                    q.setCompletedAt(now);
                    queueRepository.save(q);
                }
            }

            // 3. Free the assigned seat in database
            chair.setStatus("available");
            chair.setReservedByCustomer(null);
            chair.setReservedAt(null);
            Chair savedChair = chairRepository.save(chair);

            // 4. Check waiting queue: strictly find first ELIGIBLE customer from FIFO queue
            List<Queue> waitingList = queueRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtAsc("waiting", startOfDay);
            if (waitingList.isEmpty()) {
                waitingList = queueRepository.findByStatusOrderByCreatedAtAsc("waiting");
            }

            Queue eligibleCandidate = null;
            for (Queue candidate : waitingList) {
                if (candidate.getCustomer() == null) {
                    candidate.setStatus("cancelled");
                    candidate.setCompletedAt(now);
                    queueRepository.save(candidate);
                    continue;
                }

                // Skip already-finished entries
                String cs = candidate.getStatus().toLowerCase();
                if (cs.equals("cancelled") || cs.equals("completed") || cs.equals("skipped") || cs.equals("no_show") || cs.equals("interrupted") || cs.equals("serving") || cs.equals("called")) {
                    continue;
                }

                Long custId = candidate.getCustomer().getId();

                // Validate: Is customer already occupying ANY seat right now?
                boolean alreadySeated = chairRepository.findAll().stream()
                        .anyMatch(c -> c.getReservedByCustomer() != null && custId.equals(c.getReservedByCustomer().getId()));
                if (alreadySeated) {
                    candidate.setStatus("completed");
                    candidate.setCompletedAt(now);
                    queueRepository.save(candidate);
                    continue;
                }

                eligibleCandidate = candidate;
                break;
            }

            if (eligibleCandidate != null) {
                // Assign that eligible customer to the freed seat
                eligibleCandidate.setStatus("serving");
                eligibleCandidate.setChair(savedChair);
                eligibleCandidate.setServedAt(now);
                queueRepository.save(eligibleCandidate);

                savedChair.setStatus("occupied");
                savedChair.setReservedByCustomer(eligibleCandidate.getCustomer());
                savedChair.setReservedAt(now);
                chairRepository.save(savedChair);

                // Create or update in-progress appointment for this customer
                List<Appointment> custAppts = appointmentRepository.findByCustomerIdOrderByAppointmentDateDescCreatedAtDesc(eligibleCandidate.getCustomer().getId());
                Appointment activeAppt = null;
                for (Appointment a : custAppts) {
                    if (("pending".equalsIgnoreCase(a.getStatus()) || "confirmed".equalsIgnoreCase(a.getStatus()))
                            && (a.getAppointmentDate() == null || a.getAppointmentDate().equals(LocalDate.now()))) {
                        activeAppt = a;
                        break;
                    }
                }

                if (activeAppt != null) {
                    activeAppt.setChair(savedChair);
                    activeAppt.setStatus("in_progress");
                    activeAppt.setStartedAt(now);
                    appointmentRepository.save(activeAppt);
                } else {
                    Appointment nextAppt = new Appointment();
                    nextAppt.setCustomer(eligibleCandidate.getCustomer());
                    nextAppt.setService(eligibleCandidate.getService());
                    nextAppt.setChair(savedChair);
                    nextAppt.setTokenNumber(eligibleCandidate.getTokenNumber());
                    nextAppt.setAppointmentDate(LocalDate.now());
                    String timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
                    nextAppt.setTimeSlot(timeStr);
                    nextAppt.setStatus("in_progress");
                    nextAppt.setStartedAt(now);
                    appointmentRepository.save(nextAppt);
                }

                if (queueService != null) {
                    queueService.recalculatePositions();
                }
            } else {
                if (queueService != null) {
                    queueService.recalculatePositions();
                }
            }

            webSocketHandler.broadcast("slot-update");
            webSocketHandler.broadcast("chair-update");
            webSocketHandler.broadcast("queue-update");
            webSocketHandler.broadcast("appointment-update");

            return savedChair;
        }
    }

    public Appointment cancelAppointment(Long appointmentId, Long customerId) {
        Appointment appointment = getAppointmentById(appointmentId);

        if (customerId != null && !appointment.getCustomer().getId().equals(customerId)) {
            throw new IllegalArgumentException("Access denied. You do not own this appointment.");
        }

        if (Arrays.asList("completed", "cancelled").contains(appointment.getStatus())) {
            throw new IllegalStateException("Appointment is already " + appointment.getStatus());
        }

        appointment.setStatus("cancelled");
        Appointment saved = appointmentRepository.save(appointment);

        // Release the chair
        if (appointment.getChair() != null) {
            Chair chair = appointment.getChair();
            chair.setStatus("available");
            chair.setReservedByCustomer(null);
            chair.setReservedAt(null);
            chairRepository.save(chair);
        }

        // Cancel token and any associated queue
        boolean queueCancelled = false;
        List<Token> cancelTokens = tokenRepository.findByAppointmentId(appointment.getId());
        if (cancelTokens.isEmpty() && appointment.getTokenNumber() != null) {
            cancelTokens = tokenRepository.findByTokenNumber(appointment.getTokenNumber());
        }
        for (Token token : cancelTokens) {
            token.setStatus("cancelled");
            tokenRepository.save(token);
            if (token.getQueue() != null) {
                Queue q = token.getQueue();
                if (!"completed".equals(q.getStatus()) && !"cancelled".equals(q.getStatus())) {
                    q.setStatus("cancelled");
                    q.setCompletedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueCancelled = true;
                }
            }
        }

        if (appointment.getTokenNumber() != null) {
            List<Queue> matching = queueRepository.findByTokenNumber(appointment.getTokenNumber());
            for (Queue q : matching) {
                if (!"completed".equals(q.getStatus()) && !"cancelled".equals(q.getStatus())) {
                    q.setStatus("cancelled");
                    q.setCompletedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueCancelled = true;
                }
            }
        }

        if (queueCancelled && queueService != null) {
            queueService.recalculatePositions();
            webSocketHandler.broadcast("queue-update");
        }

        webSocketHandler.broadcast("slot-update");
        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("appointment-update");

        return saved;
    }

    public Appointment completeAppointment(Long appointmentId) {
        Appointment appointment = getAppointmentById(appointmentId);

        if ("completed".equals(appointment.getStatus())) {
            throw new IllegalStateException("Appointment is already completed");
        }

        appointment.setStatus("completed");
        appointment.setCompletedAt(LocalDateTime.now());
        Appointment saved = appointmentRepository.save(appointment);

        // Release the chair
        Chair freedChair = null;
        if (appointment.getChair() != null) {
            freedChair = appointment.getChair();
            freedChair.setStatus("available");
            freedChair.setReservedByCustomer(null);
            freedChair.setReservedAt(null);
            chairRepository.save(freedChair);
        }

        // Complete token and any associated queue entries
        boolean queueCompleted = false;
        List<Token> completeTokens = tokenRepository.findByAppointmentId(appointment.getId());
        if (completeTokens.isEmpty() && appointment.getTokenNumber() != null) {
            completeTokens = tokenRepository.findByTokenNumber(appointment.getTokenNumber());
        }
        for (Token token : completeTokens) {
            token.setStatus("used");
            tokenRepository.save(token);
            if (token.getQueue() != null) {
                Queue q = token.getQueue();
                if (!"completed".equals(q.getStatus()) && !"cancelled".equals(q.getStatus())) {
                    q.setStatus("completed");
                    q.setCompletedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueCompleted = true;
                }
            }
        }

        if (appointment.getTokenNumber() != null) {
            List<Queue> matching = queueRepository.findByTokenNumber(appointment.getTokenNumber());
            for (Queue q : matching) {
                if (!"completed".equals(q.getStatus()) && !"cancelled".equals(q.getStatus())) {
                    q.setStatus("completed");
                    q.setCompletedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueCompleted = true;
                }
            }
        }

        if (appointment.getCustomer() != null) {
            List<Queue> custQueues = queueRepository.findAllByCustomerIdAndStatusIn(
                    appointment.getCustomer().getId(), Arrays.asList("waiting", "called", "serving"));
            for (Queue q : custQueues) {
                q.setStatus("completed");
                q.setCompletedAt(LocalDateTime.now());
                if (q.getChair() != null) {
                    Chair qc = q.getChair();
                    qc.setStatus("available");
                    qc.setReservedByCustomer(null);
                    qc.setReservedAt(null);
                    chairRepository.save(qc);
                }
                queueRepository.save(q);
                queueCompleted = true;
            }
        }

        if (queueCompleted && queueService != null) {
            queueService.recalculatePositions();
        }

        // ── Auto-call next waiting customer from global token queue ──
        if (queueService != null) {
            Map<String, Object> called = queueService.callNextCustomer();
            if (called != null) {
                webSocketHandler.broadcast("slot-update");
                webSocketHandler.broadcast("queue-update");
                webSocketHandler.broadcast("chair-update");
                webSocketHandler.broadcast("appointment-update");
                return saved;
            }
        }

        webSocketHandler.broadcast("slot-update");
        webSocketHandler.broadcast("queue-update");
        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("appointment-update");

        return saved;
    }

    public Appointment startAppointment(Long appointmentId) {
        Appointment appointment = getAppointmentById(appointmentId);
        appointment.setStatus("in_progress");
        appointment.setStartedAt(LocalDateTime.now());

        // Assign chair if not assigned yet
        if (appointment.getChair() == null) {
            Chair availableChair = chairRepository.findByStatusAndIsActiveTrueOrderByChairNumberAsc("available")
                    .stream().findFirst().orElse(null);
            if (availableChair != null) {
                appointment.setChair(availableChair);
            }
        }

        Appointment saved = appointmentRepository.save(appointment);

        if (appointment.getChair() != null) {
            Chair chair = appointment.getChair();
            chair.setStatus("occupied");
            chair.setReservedByCustomer(appointment.getCustomer());
            chair.setReservedAt(LocalDateTime.now());
            chairRepository.save(chair);
        }

        // ── SYNC TO USER QUEUE: Advance status to 'serving' (SEATED) ──
        boolean queueUpdated = false;
        // 1. By token number
        if (appointment.getTokenNumber() != null) {
            List<Queue> matching = queueRepository.findByTokenNumber(appointment.getTokenNumber());
            for (Queue q : matching) {
                if (!"completed".equalsIgnoreCase(q.getStatus()) && !"cancelled".equalsIgnoreCase(q.getStatus())) {
                    q.setStatus("serving");
                    q.setChair(appointment.getChair());
                    q.setServedAt(LocalDateTime.now());
                    queueRepository.save(q);
                    queueUpdated = true;
                }
            }
        }
        // 2. By customer ID (active queue entries for this customer today)
        if (appointment.getCustomer() != null) {
            List<Queue> custQueues = queueRepository.findAllByCustomerIdAndStatusIn(
                    appointment.getCustomer().getId(), Arrays.asList("waiting", "called"));
            for (Queue q : custQueues) {
                q.setStatus("serving");
                q.setChair(appointment.getChair());
                q.setServedAt(LocalDateTime.now());
                queueRepository.save(q);
                queueUpdated = true;
            }
        }

        if (queueUpdated && queueService != null) {
            queueService.recalculatePositions();
        }

        webSocketHandler.broadcast("queue-update");
        webSocketHandler.broadcast("appointment-update");
        return saved;
    }
}
