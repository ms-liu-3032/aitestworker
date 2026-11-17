package com.company.aitest.dsh;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DshCaptureServiceTest {
    @Test
    void splitsLongCaptureIntoTraceServiceSupportedBatches() {
        List<Integer> sizes = new ArrayList<>();
        int count = DshCaptureService.saveInBatches(IntStream.range(0, 1201).boxed().toList(), batch -> {
            sizes.add(batch.size());
            return batch.size();
        });
        assertEquals(1201, count);
        assertEquals(List.of(500, 500, 201), sizes);
    }
}
