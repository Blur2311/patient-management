package com.pm.appointmentservice.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pm.appointmentservice.entity.CachedPatient;
import com.pm.appointmentservice.repository.CachedPatientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import patient.events.PatientEvent;

import java.time.Instant;
import java.util.UUID;

@Service
public class KafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumer.class);
    private final CachedPatientRepository cachedPatientRepository;

    public KafkaConsumer(CachedPatientRepository cachedPatientRepository) {
        this.cachedPatientRepository = cachedPatientRepository;
    }

    @KafkaListener(topics = {"patient.created", "patient.updated"}, groupId = "appointment-service")
    public void consumeEvent(byte[] event) {
        try {
            PatientEvent patientEvent = PatientEvent.parseFrom(event);

            log.info("Received Patient Event: {}", patientEvent.toString());

            CachedPatient cachedPatient = new CachedPatient();
            cachedPatient.setId(UUID.fromString(patientEvent.getPatientId()));
            cachedPatient.setFullName(patientEvent.getName());
            cachedPatient.setEmail(patientEvent.getEmail());
            cachedPatient.setUpdatedAt(Instant.now());

            cachedPatientRepository.save(cachedPatient);

        } catch (InvalidProtocolBufferException e) {
            log.error("Error deserializing: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error consuming event: {}", e.getMessage());
        }
    }
}
