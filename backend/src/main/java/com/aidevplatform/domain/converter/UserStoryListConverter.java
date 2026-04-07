package com.aidevplatform.domain.converter;

import com.aidevplatform.domain.model.UserStory;
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
public class UserStoryListConverter implements AttributeConverter<List<UserStory>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<UserStory>> TYPE_REF = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<UserStory> attribute) {
        if (attribute == null) return "[]";
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize user story list", e);
            return "[]";
        }
    }

    @Override
    public List<UserStory> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(dbData, TYPE_REF);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize user story list: {}", dbData, e);
            return new ArrayList<>();
        }
    }
}
