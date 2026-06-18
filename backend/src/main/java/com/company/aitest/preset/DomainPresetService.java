package com.company.aitest.preset;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class DomainPresetService {
    private static final DomainPresetDefinition GENERIC_FALLBACK = new DomainPresetDefinition(
            "GENERIC",
            "通用业务预置",
            "通用业务",
            "产品使用手册",
            "例如：产品使用手册",
            "例如：通用业务"
    );

    private final List<DomainPresetProvider> providers;

    public DomainPresetService(List<DomainPresetProvider> providers) {
        this.providers = providers;
    }

    public List<DomainPresetDefinition> listPresets() {
        List<DomainPresetDefinition> presets = new ArrayList<>();
        for (DomainPresetProvider provider : providers) {
            List<DomainPresetDefinition> provided = provider.listPresets();
            if (provided != null && !provided.isEmpty()) {
                presets.addAll(provided);
            }
        }
        if (presets.isEmpty()) {
            presets.add(GENERIC_FALLBACK);
        }
        return List.copyOf(presets);
    }

    public DomainPresetDefinition defaultPreset() {
        return listPresets().stream().findFirst().orElse(GENERIC_FALLBACK);
    }
}
