package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.MySummaryResponseDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;

public interface CommunityMyActivityService {
    PostPageResponseDTO myPosts(Long customerId, String ticker, Long cursor, int size);

    CommentPageResponseDTO myComments(Long customerId, String ticker, Long cursor, int size);

    MySummaryResponseDTO summary(Long customerId);
}
