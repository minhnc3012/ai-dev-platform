package com.aidevplatform.domain.converter;

import com.aidevplatform.domain.model.OwnerDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Converter
@Slf4j
public class OwnerDecisionListConverter implements AttributeConverter<List<OwnerDecision>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<OwnerDecision>> TYPE_REF = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<OwnerDecision> attribute) {
        if (attribute == null) return "[]";
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize owner decision list", e);
            return "[]";
        }
    }

    @Override
    public List<OwnerDecision> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(dbData, TYPE_REF);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize owner decision list: {}", dbData, e);
            return new ArrayList<>();
        }
    }
}
