package com.app.maria.domain.customer.service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/** 닉네임을 정하지 않은 고객에게 붙일 무작위 닉네임. 시드 닉네임과 같은 모양이다(형용사+동물+숫자 3~4자리, 예: 재빠른수달5588). */
final class NicknameGenerator {
    private static final List<String> ADJECTIVES =
            List.of("재빠른", "상냥한", "부지런한", "든든한", "엉뚱한", "씩씩한", "조용한", "느긋한", "용감한", "다정한");
    private static final List<String> ANIMALS =
            List.of("수달", "다람쥐", "여우", "호랑이", "고래", "판다", "거북이", "토끼", "펭귄", "너구리");
    private static final int MAX_TRIES = 30;

    private NicknameGenerator() {}

    /** 이미 쓰는 닉네임(taken)이 아닌 것을 돌려준다. 조합이 99만 가지라 30번 안에 못 찾는 일은 사실상 없다. */
    static String generate(Predicate<String> taken) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < MAX_TRIES; i++) {
            String nickname =
                    ADJECTIVES.get(random.nextInt(ADJECTIVES.size()))
                            + ANIMALS.get(random.nextInt(ANIMALS.size()))
                            + random.nextInt(100, 10_000);
            if (!taken.test(nickname)) {
                return nickname;
            }
        }
        throw new IllegalStateException("사용 가능한 닉네임을 만들지 못했습니다.");
    }
}
