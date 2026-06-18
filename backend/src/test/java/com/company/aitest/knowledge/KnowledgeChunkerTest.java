package com.company.aitest.knowledge;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    @Test
    void nullTextReturnsEmptyList() {
        assertEquals(0, chunker.chunk(null).size());
    }

    @Test
    void emptyTextReturnsEmptyList() {
        assertEquals(0, chunker.chunk("").size());
    }

    @Test
    void shortTextNotChunked() {
        String text = "这是一段短文本，只有几十个字。不应该被切片。";
        List<KnowledgeChunk> chunks = chunker.chunk(text);
        assertEquals(1, chunks.size());
        KnowledgeChunk chunk = chunks.get(0);
        assertEquals(1, chunk.chunkNo());
        assertEquals(text, chunk.content());
        assertNotNull(chunk.contentHash());
        assertEquals(64, chunk.contentHash().length());
    }

    @Test
    void longTextIsChunked() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("这是第").append(i).append("段测试文本内容，用于验证长文本切片功能是否正常工作。");
        }
        List<KnowledgeChunk> chunks = chunker.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "Long text should produce multiple chunks, got " + chunks.size());
        for (KnowledgeChunk chunk : chunks) {
            assertNotNull(chunk.content());
            assertNotNull(chunk.contentHash());
            assertEquals(64, chunk.contentHash().length());
        }
        int[] expectedChunkNos = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            expectedChunkNos[i] = chunks.get(i).chunkNo();
            assertEquals(i + 1, expectedChunkNos[i]);
        }
    }

    @Test
    void overlapIsEffective() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            sb.append("这是用于验证重叠切片的测试数据第").append(i).append("段。");
        }
        List<KnowledgeChunk> chunks = chunker.chunk(sb.toString());
        assertTrue(chunks.size() >= 2, "Should have at least 2 chunks, got " + chunks.size());

        // Concatenating all chunks should exceed original length due to overlap
        String original = sb.toString();
        int totalChunked = chunks.stream().mapToInt(c -> c.content().length()).sum();
        assertTrue(totalChunked > original.length(),
                "Total chunked length " + totalChunked + " should exceed original " + original.length());

        // Each chunk (except possibly last) should be within size bounds
        for (int i = 0; i < chunks.size() - 1; i++) {
            int len = chunks.get(i).content().length();
            assertTrue(len >= 600 && len <= 900,
                    "Chunk " + i + " size " + len + " should be in [600, 900]");
        }
    }

    @Test
    void contentHashIsStable() {
        String text = "相同的输入应该产生相同的哈希值。";
        String hash1 = KnowledgeChunker.hash(text);
        String hash2 = KnowledgeChunker.hash(text);
        String hash3 = KnowledgeChunker.hash(text);
        assertEquals(hash1, hash2);
        assertEquals(hash1, hash3);
    }

    @Test
    void differentContentHasDifferentHash() {
        String hash1 = KnowledgeChunker.hash("文本A");
        String hash2 = KnowledgeChunker.hash("文本B");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void headingPathIsDetected() {
        String text = "# 第一章\n\n这是第一章的内容。" +
                "## 第一节\n\n这是第一节的内容。这是第一节的更多内容。" +
                "### 第一小节\n\n这是第一小节的内容。";
        List<KnowledgeChunk> chunks = chunker.chunk(text);
        assertFalse(chunks.isEmpty());
        assertNotNull(chunks.get(0).headingPath());
    }

    @Test
    void chunkWithExactBoundaries() {
        KnowledgeChunker custom = new KnowledgeChunker(10, 30, 20, 5, 10, 8);
        String text = "a".repeat(100);
        List<KnowledgeChunk> chunks = custom.chunk(text);
        assertTrue(chunks.size() > 1);
        for (KnowledgeChunk c : chunks) {
            assertTrue(c.content().length() <= 30);
            assertTrue(c.content().length() >= 10);
        }
    }

    @Test
    void contentIsPreservedInChunks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("测试数据").append(i).append("。");
        }
        List<KnowledgeChunk> chunks = chunker.chunk(sb.toString());
        String reconstructed = chunks.stream().map(KnowledgeChunk::content).reduce("", String::concat);
        // with overlap, reconstructed may be longer, but original text should be contained
        String original = sb.toString();
        assertTrue(reconstructed.contains(original.substring(0, Math.min(50, original.length()))));
    }
}
