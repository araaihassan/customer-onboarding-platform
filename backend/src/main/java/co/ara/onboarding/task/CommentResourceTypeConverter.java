package co.ara.onboarding.task;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link CommentResourceType} to and from its wire string, so the column
 * actually holds {@code 'task'} / {@code 'onboarding_case'} -- matching the
 * migration's {@code comment_resource_type_ck} CHECK -- rather than the enum
 * constants' own names. {@code autoApply} means every {@code CommentResourceType}
 * field converts through this without an explicit {@code @Convert} annotation.
 */
@Converter(autoApply = true)
class CommentResourceTypeConverter implements AttributeConverter<CommentResourceType, String> {

    @Override
    public String convertToDatabaseColumn(CommentResourceType attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public CommentResourceType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : CommentResourceType.fromWireValue(dbData);
    }
}
