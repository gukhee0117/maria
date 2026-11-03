package com.app.maria.domain.community.service;

/**
 * 끼루 호출이 PENDING으로 저장됐다는 알림. 호출한 글이면 commentId가 null이고, 댓글이면 그 댓글의 id다. 답을 만드는 쪽(이후 단위)이 커밋 뒤에 받아
 * 처리한다.
 */
public record KkiruCallRequestedEvent(Long postId, Long commentId) {}
