package com.pm.appointmentservice.repository;

import com.pm.appointmentservice.entity.CachedPatient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CachedPatientRepository extends JpaRepository<CachedPatient, UUID> {
}
