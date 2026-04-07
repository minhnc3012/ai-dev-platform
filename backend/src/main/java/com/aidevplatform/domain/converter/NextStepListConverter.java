package com.aidevplatform.domain.converter;

import com.aidevplatform.domain.model.NextStep;
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
public class NextStepListConverter implements AttributeConverter<List<NextStep>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<NextStep>> TYPE_REF = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<NextStep> attribute) {
        if (attribute == null) return "[]";
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize next step list", e);
            return "[]";
        }
    }

    @Override
    public List<NextStep> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(dbData, TYPE_REF);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize next step list: {}", dbData, e);
            return new ArrayList<>();
        }
    }
}
