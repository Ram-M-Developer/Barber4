package com.barberease.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents a physical waiting chair in the waiting area.
 * DISTINCT from Chair (service seats). Customers sit here while
 * waiting for a service seat to become available.
 */
@Entity
@Table(name = "waiting_chairs")
public class WaitingChair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chair_number", nullable = false, unique = true)
    private int chairNumber;

    @Column(length = 80)
    private String name;

    /**
     * Status: VACANT or OCCUPIED
     */
    @Column(nullable = false, length = 20)
    private String status = "vacant";

    @JsonProperty("is_active")
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "occupied_by_customer_id")
    private Customer occupiedByCustomer;

    @Column(name = "occupied_at")
    private LocalDateTime occupiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getChairNumber() { return chairNumber; }
    public void setChairNumber(int chairNumber) { this.chairNumber = chairNumber; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public Customer getOccupiedByCustomer() { return occupiedByCustomer; }
    public void setOccupiedByCustomer(Customer occupiedByCustomer) { this.occupiedByCustomer = occupiedByCustomer; }

    public LocalDateTime getOccupiedAt() { return occupiedAt; }
    public void setOccupiedAt(LocalDateTime occupiedAt) { this.occupiedAt = occupiedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
