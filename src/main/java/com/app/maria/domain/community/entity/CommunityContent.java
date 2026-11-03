package com.app.maria.domain.community.entity;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommunityContent {
    public static final int MAX_LENGTH = 500;

    public static String requireValid(String content) {
        if (content == null || content.isBlank()) {
            throw new AppException(ErrorType.COMMUNITY_CONTENT_INVALID);
        }
        String stripped = content.strip();
        if (stripped.codePointCount(0, stripped.length()) > MAX_LENGTH) {
            throw new AppException(ErrorType.COMMUNITY_CONTENT_INVALID);
        }
        return stripped;
    }
}
