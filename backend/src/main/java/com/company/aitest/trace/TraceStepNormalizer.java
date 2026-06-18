package com.company.aitest.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.company.aitest.scan.ControlledScanService;

final class TraceStepNormalizer {
    private static final Set<String> NOISY_EVENTS = Set.of("SCROLL", "BLUR");
    private static final Set<String> IGNORED_KEYS = Set.of(
            "SHIFT", "CAPSLOCK", "ALT", "CONTROL", "META", "CMD", "COMMAND");
    private final List<TraceRulePack> rulePacks;

    TraceStepNormalizer() {
        this(List.of());
    }

    TraceStepNormalizer(List<TraceRulePack> rulePacks) {
        this.rulePacks = rulePacks == null ? List.of() : List.copyOf(rulePacks);
    }

    List<CleanTraceStep> normalize(List<BrowserTraceEventRecord> events, Map<Long, String> profileNames) {
        return normalize(null, events, Collections.emptyList(), profileNames, Map.of());
    }

    List<CleanTraceStep> normalize(Long projectId, List<BrowserTraceEventRecord> events, Map<Long, String> profileNames) {
        return normalize(projectId, events, Collections.emptyList(), profileNames, Map.of());
    }

    List<CleanTraceStep> normalize(List<BrowserTraceEventRecord> events,
                                   List<BrowserTraceNetworkRecord> networks,
                                   Map<Long, String> profileNames) {
        return normalize(null, events, networks, profileNames, Map.of());
    }

    List<CleanTraceStep> normalize(List<BrowserTraceEventRecord> events,
                                   List<BrowserTraceNetworkRecord> networks,
                                   Map<Long, String> profileNames,
                                   Map<String, ControlledScanService.PageScanHint> pageHints) {
        return normalize(null, events, networks, profileNames, pageHints);
    }

    List<CleanTraceStep> normalize(Long projectId,
                                   List<BrowserTraceEventRecord> events,
                                   List<BrowserTraceNetworkRecord> networks,
                                   Map<Long, String> profileNames,
                                   Map<String, ControlledScanService.PageScanHint> pageHints) {
        List<CleanTraceStep> rawSteps = new ArrayList<>();
        String lastLine = null;
        for (int i = 0; i < events.size(); i++) {
            BrowserTraceEventRecord event = events.get(i);
            MergedEvent merged = mergeEventSequence(events, i);
            i = merged.nextIndex() - 1;
            ControlledScanService.PageScanHint hint = resolvePageHint(merged.event().pageUrl(), pageHints);
            CleanTraceStep step = toStep(projectId, merged.event(), profileNames.get(merged.event().profileId()), merged.valueSummary(), hint);
            if (step == null) {
                continue;
            }
            step = enrichWithNetworkObservation(step, networks);
            if (Objects.equals(lastLine, step.description())) {
                continue;
            }
            lastLine = step.description();
            rawSteps.add(new CleanTraceStep(0, step.actor(), step.actionType(), step.description(),
                    step.pageName(), step.pageUrl(), step.relativeMs()));
        }
        return renumber(postProcessSteps(projectId, rawSteps));
    }

    List<String> buildNetworkObservations(List<BrowserTraceNetworkRecord> networks) {
        List<String> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BrowserTraceNetworkRecord network : networks) {
            boolean failed = network.failed() != null && network.failed() > 0;
            boolean abnormalStatus = network.statusCode() != null && network.statusCode() >= 400;
            if (!failed && !abnormalStatus) {
                continue;
            }
            String line = "接口 %s %s，状态=%s，耗时=%sms%s".formatted(
                    defaultText(network.method(), "GET"),
                    compactUrl(network.url()),
                    network.statusCode() == null ? "-" : network.statusCode(),
                    network.durationMs() == null ? "-" : network.durationMs(),
                    network.errorMessage() == null || network.errorMessage().isBlank()
                            ? ""
                            : "，错误=" + network.errorMessage().trim());
            if (seen.add(line)) {
                lines.add(line);
            }
        }
        if (lines.isEmpty() && !networks.isEmpty()) {
            BrowserTraceNetworkRecord first = networks.get(0);
            lines.add("本次轨迹涉及接口调用，主要页面为 %s".formatted(compactUrl(first.url())));
        }
        return lines;
    }

    private CleanTraceStep toStep(Long projectId, BrowserTraceEventRecord event, String profileName, String mergedValue,
                                  ControlledScanService.PageScanHint hint) {
        String type = defaultText(event.eventType(), "").toUpperCase(Locale.ROOT);
        if (NOISY_EVENTS.contains(type)) {
            return null;
        }
        String actor = defaultText(profileName, "默认身份");
        String pageName = bestPageName(event.pageTitle(), event.pageUrl(), hint);
        String target = bestTarget(event.elementText(), event.elementRole(), event.normalizedLocator(), event.selector());
        String value = compactValue(mergedValue != null ? mergedValue : event.valueSummary());
        String objectLabel = sanitize(event.objectLabel());
        String sectionTitle = sanitize(event.sectionTitle());
        String dialogTitle = sanitize(event.dialogTitle());

        String description = switch (type) {
            case "PAGE_OPEN" -> "打开页面“%s”".formatted(pageName);
            case "NAVIGATION" -> "跳转到页面“%s”".formatted(pageName);
            case "INPUT" -> buildInputDescription(projectId, target, value, objectLabel, sectionTitle, dialogTitle, pageName);
            case "CHANGE", "SELECT" -> buildChangeDescription(projectId, target, value, objectLabel, sectionTitle, dialogTitle, pageName);
            case "CLICK" -> buildClickDescription(projectId, target, objectLabel, dialogTitle, sectionTitle, pageName);
            case "ALERT" -> "出现提示“%s”".formatted(defaultText(target, defaultText(value, "系统提示")));
            case "DIALOG_OPEN" -> "打开弹窗“%s”".formatted(defaultText(target, "未命名弹窗"));
            case "UPLOAD" -> "上传文件到“%s”".formatted(defaultText(target, "上传控件"));
            case "KEYDOWN" -> buildKeydownDescription(value, target);
            case "SUBMIT" -> "提交当前表单";
            case "SESSION_STOP" -> "结束本轮操作";
            default -> buildFallbackDescription(type, pageName, target, value);
        };
        if (description == null || description.isBlank()) {
            return null;
        }
        return new CleanTraceStep(0, actor, type, description, pageName, event.pageUrl(), event.relativeMs());
    }

    private MergedEvent mergeEventSequence(List<BrowserTraceEventRecord> events, int startIndex) {
        BrowserTraceEventRecord first = events.get(startIndex);
        String type = defaultText(first.eventType(), "").toUpperCase(Locale.ROOT);
        if (!"INPUT".equals(type) && !"CHANGE".equals(type)) {
            return new MergedEvent(first, first.valueSummary(), startIndex + 1);
        }

        String mergedValue = first.valueSummary();
        BrowserTraceEventRecord lastRelevant = first;
        int index = startIndex + 1;
        while (index < events.size()) {
            BrowserTraceEventRecord next = events.get(index);
            if (isIgnorableKeydown(next, lastRelevant)) {
                index++;
                continue;
            }
            if (!sameFieldSequence(lastRelevant, next)) {
                break;
            }
            mergedValue = mergeInputValue(mergedValue, next.valueSummary());
            lastRelevant = next;
            index++;
        }
        return new MergedEvent(copyWithMergedValue(first, lastRelevant, mergedValue), mergedValue, index);
    }

    private boolean sameFieldSequence(BrowserTraceEventRecord base, BrowserTraceEventRecord next) {
        String nextType = defaultText(next.eventType(), "").toUpperCase(Locale.ROOT);
        if (!"INPUT".equals(nextType) && !"CHANGE".equals(nextType)) {
            return false;
        }
        return Objects.equals(base.profileId(), next.profileId())
                && Objects.equals(base.pageUrl(), next.pageUrl())
                && sameFieldIdentity(base, next);
    }


    private boolean sameFieldIdentity(BrowserTraceEventRecord base, BrowserTraceEventRecord next) {
        String baseSelector = sanitize(base.selector());
        String nextSelector = sanitize(next.selector());
        if (baseSelector != null && nextSelector != null && Objects.equals(baseSelector, nextSelector)) {
            return true;
        }
        String baseLocator = sanitize(base.normalizedLocator());
        String nextLocator = sanitize(next.normalizedLocator());
        if (baseLocator != null && nextLocator != null && Objects.equals(baseLocator, nextLocator)) {
            return true;
        }
        if (sameTextInputSequence(base, next)) {
            return true;
        }
        return Objects.equals(sanitize(base.elementText()), sanitize(next.elementText()));
    }

    private boolean sameTextInputSequence(BrowserTraceEventRecord base, BrowserTraceEventRecord next) {
        String baseRole = defaultText(base.elementRole(), "").toLowerCase(Locale.ROOT);
        String nextRole = defaultText(next.elementRole(), "").toLowerCase(Locale.ROOT);
        if (!isTextInputRole(baseRole) || !isTextInputRole(nextRole)) {
            return false;
        }
        if (!Objects.equals(sanitize(base.sectionTitle()), sanitize(next.sectionTitle()))) {
            return false;
        }
        if (!Objects.equals(sanitize(base.dialogTitle()), sanitize(next.dialogTitle()))) {
            return false;
        }
        String baseText = sanitize(base.elementText());
        String nextText = sanitize(next.elementText());
        String baseValue = sanitize(base.valueSummary());
        String nextValue = sanitize(next.valueSummary());
        if (baseText != null && nextText != null && Objects.equals(baseText, nextText)) {
            return true;
        }
        if (baseValue != null && nextText != null && looksLikeInputProgression(baseValue, nextText)) {
            return true;
        }
        if (baseText != null && nextValue != null && looksLikeInputProgression(baseText, nextValue)) {
            return true;
        }
        if (baseValue != null && nextValue != null && looksLikeInputProgression(baseValue, nextValue)) {
            return true;
        }
        return false;
    }

    private boolean isTextInputRole(String role) {
        return "input".equals(role) || "textarea".equals(role) || "textbox".equals(role) || "searchbox".equals(role);
    }

    private boolean looksLikeInputProgression(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String a = normalizeTypingCandidate(left);
        String b = normalizeTypingCandidate(right);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        return b.startsWith(a) || a.startsWith(b) || b.contains(a) || a.contains(b);
    }

    private String normalizeTypingCandidate(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
    }

    private boolean isIgnorableKeydown(BrowserTraceEventRecord event, BrowserTraceEventRecord base) {
        String type = defaultText(event.eventType(), "").toUpperCase(Locale.ROOT);
        if (!"KEYDOWN".equals(type)) {
            return false;
        }
        String key = defaultText(event.valueSummary(), event.elementText()).toUpperCase(Locale.ROOT);
        return Objects.equals(base.profileId(), event.profileId())
                && Objects.equals(base.pageUrl(), event.pageUrl())
                && (IGNORED_KEYS.contains(key) || isCommonTypingKey(key));
    }

    private boolean isCommonTypingKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return key.length() == 1
                || "BACKSPACE".equals(key)
                || "DELETE".equals(key)
                || "SPACE".equals(key);
    }

    private BrowserTraceEventRecord copyWithMergedValue(BrowserTraceEventRecord base,
                                                        BrowserTraceEventRecord lastRelevant,
                                                        String mergedValue) {
        return new BrowserTraceEventRecord(
                base.id(),
                base.traceGroupId(),
                base.traceSessionId(),
                base.profileId(),
                base.eventType(),
                lastRelevant.pageUrl(),
                lastRelevant.pageTitle(),
                base.elementText(),
                base.elementRole(),
                base.selector(),
                mergedValue,
                lastRelevant.screenshotPath(),
                base.normalizedLocator(),
                base.sectionTitle(),
                base.dialogTitle(),
                base.objectLabel(),
                lastRelevant.happenedAtUtc(),
                lastRelevant.happenedAtLocal(),
                lastRelevant.timezone(),
                lastRelevant.relativeMs(),
                lastRelevant.createdAt()
        );
    }

    private String mergeInputValue(String current, String next) {
        String left = sanitize(current);
        String right = sanitize(next);
        if (left == null || left.isBlank()) return right;
        if (right == null || right.isBlank()) return left;
        if (left.length() == 1 && right.length() == 1 && !right.startsWith(left) && !left.startsWith(right)) {
            return left + right;
        }
        return right;
    }

    private CleanTraceStep enrichWithNetworkObservation(CleanTraceStep step, List<BrowserTraceNetworkRecord> networks) {
        if (!"CLICK".equals(step.actionType()) && !"SUBMIT".equals(step.actionType())) {
            return step;
        }
        BrowserTraceNetworkRecord nearby = findNearbyNetwork(step, networks);
        if (nearby == null) {
            return step;
        }
        if (isNoiseNetwork(nearby)) {
            return step;
        }
        String url = compactUrl(nearby.url());
        String suffix = "，触发接口 %s %s".formatted(defaultText(nearby.method(), "GET"), url);
        return new CleanTraceStep(step.stepNo(), step.actor(), step.actionType(),
                step.description() + suffix, step.pageName(), step.pageUrl(), step.relativeMs());
    }

    private boolean isNoiseNetwork(BrowserTraceNetworkRecord network) {
        String url = compactUrl(network.url());
        if (url == null || url.isBlank()) {
            return true;
        }
        return url.contains("/api/wx/getServerTime");
    }

    private BrowserTraceNetworkRecord findNearbyNetwork(CleanTraceStep step, List<BrowserTraceNetworkRecord> networks) {
        if (step.relativeMs() == null || networks == null || networks.isEmpty()) {
            return null;
        }
        for (BrowserTraceNetworkRecord network : networks) {
            long relativeMs = network.relativeMs() == null ? -1L : network.relativeMs();
            if (relativeMs >= step.relativeMs() && relativeMs - step.relativeMs() <= 2500) {
                return network;
            }
        }
        return null;
    }

    private String buildInputDescription(Long projectId, String target, String value, String objectLabel, String sectionTitle, String dialogTitle, String pageName) {
        String field = normalizeField(defaultText(target, "字段"));
        TraceRulePack.DescriptionDecision decision = applyDescriptionOverride(
                pack -> pack.describeInput(new TraceDescriptionContext(projectId, field, value, objectLabel, sectionTitle, dialogTitle, pageName)));
        if (decision.handled()) {
            return decision.description();
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return "在“%s”输入%s".formatted(field, value);
    }

    private String buildChangeDescription(Long projectId, String target, String value, String objectLabel, String sectionTitle, String dialogTitle, String pageName) {
        String field = normalizeField(defaultText(target, "选项"));
        TraceRulePack.DescriptionDecision decision = applyDescriptionOverride(
                pack -> pack.describeChange(new TraceDescriptionContext(projectId, field, value, objectLabel, sectionTitle, dialogTitle, pageName)));
        if (decision.handled()) {
            return decision.description();
        }
        if (value == null || value.isBlank()) {
            return "选择“%s”".formatted(field);
        }
        return "在“%s”选择%s".formatted(field, value);
    }


    private String normalizeField(String text) {
        String cleaned = sanitize(text);
        if (cleaned == null) {
            return null;
        }
        cleaned = cleaned.replace("请输入", "输入")
                .replace("请选择", "选择")
                .replace("请添加", "添加")
                .replace("86 · 输入", "输入")
                .replaceAll("确\\s*定", "确定")
                .replaceAll("提\\s*交", "提交")
                .replaceAll("取\\s*消", "取消")
                .replaceAll("详\\s*情\\s*及\\s*操\\s*作", "详情及操作");
        return cleaned;
    }

    private String buildClickDescription(Long projectId, String target, String objectLabel, String dialogTitle, String sectionTitle, String pageName) {
        String action = normalizeField(defaultText(target, "按钮"));
        TraceRulePack.DescriptionDecision decision = applyDescriptionOverride(
                pack -> pack.describeClick(new TraceDescriptionContext(projectId, action, null, objectLabel, sectionTitle, dialogTitle, pageName)));
        if (decision.handled()) {
            return decision.description();
        }
        String cleanObjectLabel = objectLabel;
        if (cleanObjectLabel != null && action != null) {
            // 当 elementText 已经包含整行文本时，避免 “在“xxx”行点击“xxx”” 这种重复
            if (cleanObjectLabel.equals(action) || action.contains(cleanObjectLabel)) {
                cleanObjectLabel = null;
            }
        }
        boolean confirmAction = action.contains("提交") || action.contains("保存") || action.contains("查询")
                || action.contains("取消") || action.contains("确认") || action.contains("登录");
        boolean listAction = action.contains("修改") || action.contains("编辑") || action.contains("删除")
                || action.contains("查看") || action.contains("详情");

        String prefix = "";
        if (listAction && cleanObjectLabel != null && !cleanObjectLabel.isBlank()) {
            prefix = "在“%s”行".formatted(cleanObjectLabel);
        } else if (dialogTitle != null && !dialogTitle.isBlank() && !action.contains(dialogTitle)) {
            prefix = "在“%s”弹窗".formatted(dialogTitle);
        }

        if (confirmAction) {
            return prefix.isEmpty() ? "点击“%s”".formatted(action) : "%s点击“%s”".formatted(prefix, action);
        }
        return prefix.isEmpty() ? "点击“%s”按钮".formatted(action) : "%s点击“%s”按钮".formatted(prefix, action);
    }

    private String buildKeydownDescription(String value, String target) {
        String key = defaultText(value, "").toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ENTER" -> target == null || target.isBlank()
                    ? "按下回车键提交或确认当前操作"
                    : "在“%s”按下回车键".formatted(target);
            case "TAB" -> "按下 Tab 键切换到下一个输入项";
            case "ESCAPE" -> "按下 Esc 键关闭当前弹层或取消当前操作";
            default -> null;
        };
    }

    private String buildFallbackDescription(String type, String pageName, String target, String value) {
        StringBuilder builder = new StringBuilder();
        if (!pageName.isBlank()) {
            builder.append("在页面“").append(pageName).append("”");
        }
        if (target != null && !target.isBlank()) {
            builder.append("操作“").append(target).append("”");
        } else {
            builder.append("执行操作");
        }
        if (value != null && !value.isBlank()) {
            builder.append("，内容为").append(value);
        }
        if (type != null && !type.isBlank()) {
            builder.append("（").append(type).append("）");
        }
        return builder.toString();
    }

    private ControlledScanService.PageScanHint resolvePageHint(String pageUrl,
                                                               Map<String, ControlledScanService.PageScanHint> pageHints) {
        if (pageHints == null || pageHints.isEmpty() || pageUrl == null || pageUrl.isBlank()) {
            return null;
        }
        return pageHints.get(normalizeRoutePath(pageUrl));
    }

    private String bestPageName(String title, String url, ControlledScanService.PageScanHint hint) {
        String runtime = null;
        if (title != null && !title.isBlank()) {
            runtime = normalizePageName(title);
        } else if (url != null && !url.isBlank()) {
            String compact = compactUrl(url);
            runtime = compact.isBlank() ? null : compact;
        }
        String hinted = hint == null ? null : buildHintedPageName(hint);
        if (runtime == null || runtime.isBlank()) {
            return defaultText(hinted, "未命名页面");
        }
        if (hinted == null || hinted.isBlank()) {
            return runtime;
        }
        if (runtime.contains(" / ") && runtime.length() >= hinted.length()) {
            return runtime;
        }
        if (runtime.equals(hinted) || runtime.endsWith(hinted) || hinted.endsWith(runtime)) {
            return runtime.length() >= hinted.length() ? runtime : hinted;
        }
        return hinted;
    }

    private String buildHintedPageName(ControlledScanService.PageScanHint hint) {
        List<String> parts = new ArrayList<>();
        if (hint.breadcrumbPath() != null && !hint.breadcrumbPath().isBlank()) {
            for (String part : hint.breadcrumbPath().split("\\s*/\\s*")) {
                String cleaned = sanitize(part);
                if (cleaned != null && !parts.contains(cleaned)) {
                    parts.add(cleaned);
                }
            }
        }
        String label = sanitize(hint.pageLabel());
        if (label != null && !parts.contains(label)) {
            parts.add(label);
        }
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    private String normalizeRoutePath(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return null;
        }
        int protocol = pageUrl.indexOf("://");
        String text = pageUrl.trim();
        if (protocol >= 0) {
            int pathStart = text.indexOf('/', protocol + 3);
            text = pathStart >= 0 ? text.substring(pathStart) : "/";
        }
        int query = text.indexOf('?');
        return query >= 0 ? text.substring(0, query) : text;
    }

    private String bestTarget(String elementText, String elementRole, String normalizedLocator, String selector) {
        if (elementText != null && !elementText.isBlank()) {
            return normalizeTargetText(elementText);
        }
        if (elementRole != null && !elementRole.isBlank()) {
            return normalizeTargetText(elementRole);
        }
        // Sprint 4 · T-PW-1：normalized_locator 优先于原始 selector，因为它已经过 Playwright 的语义化处理
        if (normalizedLocator != null && !normalizedLocator.isBlank()) {
            String t = normalizeLocatorToTarget(normalizedLocator);
            if (t != null && !t.isBlank()) return t;
        }
        if (selector == null || selector.isBlank()) {
            return null;
        }
        String normalized = selector.replace("#", " ").replace(".", " ")
                .replace("[", " ").replace("]", " ").replace("=", " ");
        normalized = normalized.replace("input", "输入框").replace("button", "按钮").replace("select", "下拉框");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return sanitize(normalized);
    }

    /**
     * 把 Playwright `locator.normalize()` 的输出（形如 `getByRole('button', { name: '保存' })`
     * 或 `getByLabel('用户名')`）映射成中文目标描述，作为兜底前的优先回退。
     */
    private String normalizeLocatorToTarget(String normalizedLocator) {
        if (normalizedLocator == null) return null;
        String s = normalizedLocator.strip();
        // 1. getByRole('button', { name: 'X' })  → "X 按钮"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("getByRole\\(\\s*['\"]([a-z]+)['\"]\\s*,?\\s*\\{?[^)]*name\\s*[:=]\\s*['\"]([^'\"]+)['\"]")
                .matcher(s);
        if (m.find()) {
            String role = m.group(1);
            String name = m.group(2);
            return switch (role) {
                case "button" -> name + " 按钮";
                case "link" -> name + " 链接";
                case "tab" -> name + " 标签页";
                case "checkbox" -> name + " 复选框";
                case "radio" -> name + " 单选";
                case "menuitem" -> name + " 菜单项";
                case "textbox", "searchbox" -> name + " 输入框";
                default -> name;
            };
        }
        // 2. getByLabel('X') / getByText('X') / getByPlaceholder('X')
        m = java.util.regex.Pattern
                .compile("getBy(Label|Text|Placeholder|TestId)\\(\\s*['\"]([^'\"]+)['\"]")
                .matcher(s);
        if (m.find()) {
            return m.group(2);
        }
        return null;
    }

    private String normalizePageName(String title) {
        List<String> parts = new ArrayList<>();
        for (String part : title.split("[/＞>｜|]+")) {
            String cleaned = sanitize(part);
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            if (parts.isEmpty() || !parts.get(parts.size() - 1).equals(cleaned)) {
                parts.add(cleaned);
            }
        }
        return parts.isEmpty() ? sanitize(title) : String.join(" / ", parts);
    }

    private String normalizeTargetText(String text) {
        String cleaned = sanitize(text);
        if (cleaned == null || cleaned.isBlank()) {
            return cleaned;
        }
        cleaned = cleaned
                .replace("请输入", "输入")
                .replace("请选择", "选择")
                .replace("请搜索", "搜索");
        return switch (cleaned) {
            case "svg", "use" -> "图标按钮";
            case "on" -> "已勾选";
            default -> cleaned;
        };
    }

    private String compactUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String text = url.trim();
        int protocol = text.indexOf("://");
        if (protocol >= 0) {
            int pathStart = text.indexOf('/', protocol + 3);
            if (pathStart >= 0 && pathStart < text.length() - 1) {
                return text.substring(pathStart);
            }
        }
        return text;
    }

    private String compactValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim().replaceAll("\\s+", " ");
        if (text.length() > 48) {
            return text.substring(0, 45) + "...";
        }
        return text;
    }

    private String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    private String defaultText(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text.trim();
    }

    private List<CleanTraceStep> postProcessSteps(Long projectId, List<CleanTraceStep> steps) {
        List<CleanTraceStep> result = steps;
        for (TraceRulePack rulePack : rulePacks) {
            result = rulePack.postProcess(projectId, result);
        }
        return result;
    }

    private List<CleanTraceStep> renumber(List<CleanTraceStep> steps) {
        List<CleanTraceStep> renumbered = new ArrayList<>(steps.size());
        int stepNo = 1;
        for (CleanTraceStep step : steps) {
            renumbered.add(new CleanTraceStep(stepNo++, step.actor(), step.actionType(), step.description(),
                    step.pageName(), step.pageUrl(), step.relativeMs()));
        }
        return renumbered;
    }

    record CleanTraceStep(int stepNo, String actor, String actionType, String description,
                          String pageName, String pageUrl, Long relativeMs) {
    }

    private record MergedEvent(BrowserTraceEventRecord event, String valueSummary, int nextIndex) {
    }

    private TraceRulePack.DescriptionDecision applyDescriptionOverride(
            Function<TraceRulePack, TraceRulePack.DescriptionDecision> extractor) {
        for (TraceRulePack rulePack : rulePacks) {
            TraceRulePack.DescriptionDecision decision = extractor.apply(rulePack);
            if (decision != null && decision.handled()) {
                return decision;
            }
        }
        return TraceRulePack.DescriptionDecision.pass();
    }
}
