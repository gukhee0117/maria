package com.app.maria.domain.news.service;

import com.app.maria.domain.news.dto.NewsContract.Answer;
import com.app.maria.domain.news.dto.NewsContract.Briefing;
import com.app.maria.domain.news.dto.NewsContract.QuestionRequest;

/** Customer-facing operations for personalized financial news. */
public interface NewsBriefingOperations {
    Briefing briefing(Long customerId);

    Answer summary(Long customerId, String id, String version);

    Answer question(Long customerId, String id, QuestionRequest request);
}
