package com.kavindu.patientservice.service;

import com.kavindu.patientservice.dto.PatientResponseDTO;
import com.kavindu.patientservice.mapper.PatientMapper;
import com.kavindu.patientservice.model.Patient;
import com.kavindu.patientservice.repository.PatientRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PatientService {

    private PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    public List<PatientResponseDTO> getPatients() {
        List<Patient> patients = patientRepository.findAll();
        List<PatientResponseDTO> patientResponseDTOs = patients.stream()
                .map(patient -> PatientMapper.toDto(patient)).toList();
        return patientResponseDTOs;
    }
}
