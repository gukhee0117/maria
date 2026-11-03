package com.app.maria.domain.community.dto.response;

/**
 * 오늘 화제인 종목. rank는 1부터이고 화제 점수는 오늘 글 수 + 오늘 이용자 댓글 수다. name은 종목 마스터에서 찾지 못하면 null이다. reason은 끼루가 오늘
 * 글을 보고 쓴 한 줄 이유이고, 아직 만들어지지 않았거나 만들 만한 이야기가 없으면 null이다(조회는 이유를 기다리지 않는다).
 */
public record TickerRankResponseDTO(
        int rank, String ticker, String name, long postCount, long commentCount, String reason) {}
