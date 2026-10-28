package com.app.maria.domain.investorassessment.entity.choice;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import java.io.IOException;

/**
 * 보기를 enum 이름 문자열로만 받는다. Jackson 기본 동작은 숫자(2)나 숫자 문자열("2")을 enum 선언 순서로 바꿔 받아서, 보기 순서에 API 의미가 매이게
 * 된다.
 */
public class ChoiceNameDeserializer extends JsonDeserializer<ScoredChoice>
        implements ContextualDeserializer {
    private final Class<?> choiceType;

    /** Jackson이 처음 만드는 껍데기. 실제 보기 타입은 createContextual에서 정해진다. */
    public ChoiceNameDeserializer() {
        this(null);
    }

    private ChoiceNameDeserializer(Class<?> choiceType) {
        this.choiceType = choiceType;
    }

    @Override
    public JsonDeserializer<?> createContextual(
            DeserializationContext ctxt, BeanProperty property) {
        JavaType type = ctxt.getContextualType();
        if (type == null && property != null) {
            type =
                    property.getType().getContentType() != null
                            ? property.getType().getContentType()
                            : property.getType();
        }
        return new ChoiceNameDeserializer(type.getRawClass());
    }

    @Override
    public ScoredChoice deserialize(JsonParser parser, DeserializationContext ctxt)
            throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (ScoredChoice) ctxt.handleUnexpectedToken(choiceType, parser);
        }
        String text = parser.getText();
        for (Object constant : choiceType.getEnumConstants()) {
            ScoredChoice choice = (ScoredChoice) constant;
            if (choice.name().equals(text)) {
                return choice;
            }
        }
        return (ScoredChoice) ctxt.handleWeirdStringValue(choiceType, text, "보기 이름이 아닙니다");
    }
}
