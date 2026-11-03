package com.app.maria.domain.community.kkiru;

/** 끼루를 부른 글 또는 댓글. commentId가 null이면 글 본문에서 부른 것이다. ticker가 null이면 종목 없는 글이다. */
public record KkiruQuestion(Long postId, Long commentId, String ticker, String text) {}
