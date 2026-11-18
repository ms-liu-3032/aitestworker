package com.company.aitest.dsh;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DshCaseBatchService {
    private final DshDraftService drafts;
    private final AutomationAssetService assets;

    public DshCaseBatchService(DshDraftService drafts, AutomationAssetService assets) {
        this.drafts = drafts;
        this.assets = assets;
    }

    public record Item(DshDraftService.DraftInput draft, String assetRequestKey,
                       String assetType, JsonNode mapping) {}
    public record BatchInput(Long workSessionId, List<Item> items) {}
    public record ItemResult(int index, Long draftId, Long assetId) {}

    @Transactional
    public List<ItemResult> create(Long projectId, BatchInput input, CurrentUser user) {
        if (input == null || input.workSessionId() == null || input.workSessionId() < 1
                || input.items() == null || input.items().isEmpty() || input.items().size() > 100)
            throw new BusinessException("每批需指定任务和 1–100 条用例");
        Set<String> keys = new HashSet<>();
        for (Item item : input.items()) {
            if (item == null || item.draft() == null || !input.workSessionId().equals(item.draft().workSessionId())
                    || item.draft().idempotencyKey() == null || !keys.add(item.draft().idempotencyKey()))
                throw new BusinessException("批内草稿须使用同一任务和互不重复的幂等键");
            if (item.mapping() != null) {
                if (!(item.mapping() instanceof ObjectNode) || item.assetRequestKey() == null
                        || item.assetRequestKey().isBlank() || !keys.add(item.assetRequestKey()))
                    throw new BusinessException("执行映射须为对象，并使用独立的资产幂等键");
            }
        }
        List<ItemResult> result = new ArrayList<>();
        for (int i = 0; i < input.items().size(); i++) {
            Item item = input.items().get(i);
            Long draftId = drafts.save(projectId, item.draft(), user).draftId();
            Long assetId = null;
            if (item.mapping() != null) {
                ObjectNode mapping = ((ObjectNode) item.mapping()).deepCopy();
                mapping.put("caseNo", "DSH-" + draftId);
                assetId = assets.create(projectId, new AutomationAssetService.CreateInput(
                        input.workSessionId(), item.assetRequestKey(), "DRAFT", draftId,
                        item.assetType(), mapping), user).id();
            }
            result.add(new ItemResult(i + 1, draftId, assetId));
        }
        return result;
    }
}
