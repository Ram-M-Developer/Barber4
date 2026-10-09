package com.barberease.repositories;

import com.barberease.models.WaitingChair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WaitingChairRepository extends JpaRepository<WaitingChair, Long> {
    List<WaitingChair> findByIsActiveTrueOrderByChairNumberAsc();
    List<WaitingChair> findByStatusAndIsActiveTrueOrderByChairNumberAsc(String status);
    Optional<WaitingChair> findByChairNumber(int chairNumber);
    long countByStatus(String status);
}
