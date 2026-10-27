package com.app.maria.domain.chat.loader;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class KkiruKnowledgeLoaderTest {

    @Mock private VectorStore vectorStore;

    private KkiruKnowledgeLoader loader;

    @BeforeEach
    void setUp() {
        loader = new KkiruKnowledgeLoader(vectorStore);
        ReflectionTestUtils.setField(loader, "docsPath", "classpath:kkiru/nonexistent/*.txt");
    }

    @Test
    void 문서_없으면_VectorStore에_아무것도_추가하지_않는다() {
        loader.load();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void 문서_없어도_예외가_발생하지_않는다() {
        assertThatCode(() -> loader.load()).doesNotThrowAnyException();
    }
}
