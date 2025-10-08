package com.pm.appointmentservice.repository;

import com.pm.appointmentservice.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    List<Appointment> findByStartTimeBetween(LocalDateTime startTime, LocalDateTime endTime);
}
