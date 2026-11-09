package com.app.maria.domain.news.dto;

import com.app.maria.domain.chat.dto.request.MessageDTO;
import com.app.maria.domain.customer.entity.InvestorType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NewsContract {
    private NewsContract() {}

    public enum Origin {
        LIVE_LLM,
        CACHED_LLM,
        PREPARED
    }

    public enum PreparedReason {
        DEMO_DEFAULT,
        LLM_UNAVAILABLE,
        POLICY_REFUSAL,
        ARTICLE_EVIDENCE
    }

    public enum PersonalizationStatus {
        ACTIVE,
        UNASSIGNED,
        EXPIRED
    }

    public record Context(
            Long customerId,
            Set<String> tickers,
            InvestorType investorType,
            PersonalizationStatus personalizationStatus,
            Map<String, String> stockNames,
            Map<String, String> stockSectors) {
        public Context {
            tickers = Set.copyOf(tickers);
            stockNames = Map.copyOf(stockNames);
            stockSectors = Map.copyOf(stockSectors);
        }

        public Context(
                Long customerId,
                Set<String> tickers,
                InvestorType type,
                PersonalizationStatus status,
                Map<String, String> stockNames) {
            this(customerId, tickers, type, status, stockNames, Map.of());
        }

        public Context(
                Long customerId,
                Set<String> tickers,
                InvestorType type,
                PersonalizationStatus status) {
            this(customerId, tickers, type, status, Map.of());
        }
    }

    public record Selected(NewsArticle article, int score) {}

    public record Briefing(
            Long customerId,
            InvestorType investorType,
            PersonalizationStatus personalizationStatus,
            String selectionRuleVersion,
            List<Selected> articles,
            String contextVersion,
            String summaryVersion,
            Answer firstSummary,
            List<String> themes) {
        public Briefing(
                Long customerId,
                InvestorType investorType,
                PersonalizationStatus personalizationStatus,
                String selectionRuleVersion,
                List<Selected> articles,
                String contextVersion,
                String summaryVersion,
                Answer firstSummary) {
            this(
                    customerId,
                    investorType,
                    personalizationStatus,
                    selectionRuleVersion,
                    articles,
                    contextVersion,
                    summaryVersion,
                    firstSummary,
                    List.of());
        }

        public Briefing(
                Long customerId,
                InvestorType investorType,
                PersonalizationStatus personalizationStatus,
                String selectionRuleVersion,
                List<Selected> articles) {
            this(
                    customerId,
                    investorType,
                    personalizationStatus,
                    selectionRuleVersion,
                    articles,
                    "legacy",
                    "legacy",
                    null);
        }
    }

    public record SummaryRequest(@NotBlank @Size(max = 64) String articleVersion) {}

    public record QuestionRequest(
            @NotBlank @Size(max = 64) String articleVersion,
            @NotBlank @Size(max = 1000) String question,
            @NotNull @Size(max = 6) List<@NotNull @Valid MessageDTO> messages) {}

    public record Evidence(List<String> facts, List<String> risks) {}

    public record RelatedStock(String ticker, String name, String sectorName) {}

    public record Answer(
            String articleId,
            String articleVersion,
            Evidence evidence,
            String explanation,
            Origin origin,
            PreparedReason preparedReason,
            Instant generatedAt,
            List<String> points,
            String quote,
            List<RelatedStock> relatedStocks) {}
}
