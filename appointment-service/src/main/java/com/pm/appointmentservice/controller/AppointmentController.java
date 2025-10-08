package com.pm.appointmentservice.controller;

import com.pm.appointmentservice.dto.AppointmentResponseDto;
import com.pm.appointmentservice.service.AppointmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/appointments")
public class AppointmentController {
    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    public List<AppointmentResponseDto> getAppointmentsByDateRange(
            @RequestParam("from") LocalDateTime from,
            @RequestParam("to") LocalDateTime to
    ) {
        return appointmentService.getAllAppointmentsByDateRange(from, to);
    }
}
