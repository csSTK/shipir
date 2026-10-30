package dev.bethge.shipir.proxy;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
class BackendSchemeConverter implements AttributeConverter<BackendScheme, String> {

    @Override
    public String convertToDatabaseColumn(BackendScheme attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public BackendScheme convertToEntityAttribute(String dbData) {
        return dbData == null ? null : BackendScheme.fromValue(dbData);
    }
}
