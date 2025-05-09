package com.kavindu.patientservice.service;

import com.kavindu.patientservice.dto.PatientRequestDTO;
import com.kavindu.patientservice.dto.PatientResponseDTO;
import com.kavindu.patientservice.exception.EmailAlreadyExistsException;
import com.kavindu.patientservice.mapper.PatientMapper;
import com.kavindu.patientservice.model.Patient;
import com.kavindu.patientservice.repository.PatientRepository;
import org.springframework.stereotype.Service;

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
                .map(patient -> PatientMapper.toDTO(patient)).toList();
        return patientResponseDTOs;
    }

    public PatientResponseDTO createPatient(PatientRequestDTO patientRequestDTO) {

        if(patientRepository.existsByEmail(patientRequestDTO.getEmail())) {
            throw new EmailAlreadyExistsException("A patient with this email already exists"+ patientRequestDTO.getEmail());

        }
        Patient newPatient = patientRepository.save(PatientMapper.toModel(patientRequestDTO));
        return PatientMapper.toDTO(newPatient);
    }
}
