package com.app.maria.domain.chat.loader;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KkiruKnowledgeLoader {

    private final VectorStore vectorStore;

    @Value("${kkiru.docs.path:classpath:kkiru/docs/*.txt}")
    private String docsPath;

    public KkiruKnowledgeLoader(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void load() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(docsPath);
        } catch (IOException e) {
            log.warn("끼루 지식베이스 경로 조회 실패: {}", docsPath, e);
            return;
        }

        if (resources.length == 0) {
            log.info("끼루 지식베이스 문서 없음. 경로: {}", docsPath);
            return;
        }

        TokenTextSplitter splitter = new TokenTextSplitter();
        for (Resource resource : resources) {
            try {
                TextReader reader = new TextReader(resource);
                List<Document> chunks = splitter.apply(reader.get());
                vectorStore.add(chunks);
                log.info(
                        "끼루 지식베이스 로딩 완료: {} ({} 청크)",
                        resource.getFilename(),
                        chunks.size());
            } catch (Exception e) {
                log.error("끼루 지식베이스 로딩 실패: {}", resource.getFilename(), e);
            }
        }
    }
}
