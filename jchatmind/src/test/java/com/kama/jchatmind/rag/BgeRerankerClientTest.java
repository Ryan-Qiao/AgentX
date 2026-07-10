package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.rag.RagSearchResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BgeRerankerClientTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reranksCandidatesByBgeScores() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rerank", exchange -> {
            byte[] body = """
                    {"scores":[0.12,0.93]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        BgeRerankerClient client = new BgeRerankerClient(WebClient.builder());
        String endpoint = "http://localhost:%d/rerank".formatted(server.getAddress().getPort());

        List<RagSearchResult> reranked = client.rerank(
                "Agent trace 怎么做",
                List.of(
                        result("c1", "天气查询工具"),
                        result("c2", "Agent trace 记录工具调用和模型输出")
                ),
                endpoint,
                "BAAI/bge-reranker-v2-m3",
                3000
        );

        assertThat(reranked).extracting(RagSearchResult::getChunkId).containsExactly("c2", "c1");
        assertThat(reranked.get(0).getRerankScore()).isEqualTo(0.93);
        assertThat(reranked.get(0).getLexicalScore()).isNull();
    }

    private RagSearchResult result(String chunkId, String content) {
        return RagSearchResult.builder()
                .chunkId(chunkId)
                .documentTitle("test.md")
                .content(content)
                .score(0.5)
                .build();
    }
}
