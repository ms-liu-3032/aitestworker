package com.company.aitest.trace;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
class ConfiguredTraceRulePack implements TraceRulePack {

    private final List<LoadedPack> classpathPacks;
    private final TraceRulePackConfigRepository repository;
    private final Map<String, TraceWorkflowProcessor> workflowProcessors;

    ConfiguredTraceRulePack() {
        this(defaultWorkflowProcessors(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ConfiguredTraceRulePack(TraceRulePackConfigRepository repository) {
        this(defaultWorkflowProcessors(), repository);
    }

    ConfiguredTraceRulePack(List<TraceWorkflowProcessor> workflowProcessors) {
        this(workflowProcessors, null);
    }

    private ConfiguredTraceRulePack(List<TraceWorkflowProcessor> workflowProcessors,
                                    TraceRulePackConfigRepository repository) {
        this.classpathPacks = loadClasspathPacks();
        this.repository = repository;
        this.workflowProcessors = new LinkedHashMap<>();
        for (TraceWorkflowProcessor processor : TraceWorkflowSupport.safeList(workflowProcessors)) {
            this.workflowProcessors.put(processor.kind(), processor);
        }
    }

    /**
     * 测试用构造器：允许显式指定规则包，不从 classpath 自动加载。
     */
    ConfiguredTraceRulePack(List<TraceRuleSetConfig> explicitPacks,
                             List<TraceWorkflowProcessor> workflowProcessors) {
        this.classpathPacks = new ArrayList<>();
        for (TraceRuleSetConfig config : explicitPacks) {
            this.classpathPacks.add(new LoadedPack(
                    defaultText(config.name(), "explicit"),
                    config
            ));
        }
        this.repository = null;
        this.workflowProcessors = new LinkedHashMap<>();
        for (TraceWorkflowProcessor processor : TraceWorkflowSupport.safeList(workflowProcessors)) {
            this.workflowProcessors.put(processor.kind(), processor);
        }
    }

    private static List<TraceWorkflowProcessor> defaultWorkflowProcessors() {
        return List.of(
                new DialogInputConfirmWorkflowProcessor(),
                new EntryNavigationSubmitWorkflowProcessor()
        );
    }

    @Override
    public DescriptionDecision describeInput(TraceDescriptionContext context) {
        for (LoadedPack pack : activePacks(context.projectId())) {
            DescriptionDecision configured = applyDescribeRules(pack.config(), pack.config().inputRules(), context);
            if (configured.handled()) {
                return configured;
            }
        }
        return DescriptionDecision.pass();
    }

    @Override
    public DescriptionDecision describeChange(TraceDescriptionContext context) {
        for (LoadedPack pack : activePacks(context.projectId())) {
            DescriptionDecision configured = applyDescribeRules(pack.config(), pack.config().changeRules(), context);
            if (configured.handled()) {
                return configured;
            }
        }
        return DescriptionDecision.pass();
    }

    @Override
    public DescriptionDecision describeClick(TraceDescriptionContext context) {
        for (LoadedPack pack : activePacks(context.projectId())) {
            DescriptionDecision configured = applyDescribeRules(pack.config(), pack.config().clickRules(), context);
            if (configured.handled()) {
                return configured;
            }
        }
        return DescriptionDecision.pass();
    }

    @Override
    public List<TraceStepNormalizer.CleanTraceStep> postProcess(List<TraceStepNormalizer.CleanTraceStep> steps) {
        return postProcess(null, steps);
    }

    @Override
    public List<TraceStepNormalizer.CleanTraceStep> postProcess(Long projectId, List<TraceStepNormalizer.CleanTraceStep> steps) {
        List<TraceStepNormalizer.CleanTraceStep> result = steps;
        for (LoadedPack pack : activePacks(projectId)) {
            for (TraceRuleSetConfig.WorkflowRule workflow : safeList(pack.config().workflows())) {
                TraceWorkflowProcessor processor = workflowProcessors.get(defaultText(workflow.kind(), ""));
                if (processor != null) {
                    result = processor.apply(result, pack.config(), defaultText(workflow.ref(), ""));
                }
            }
        }
        return result;
    }

    @Override
    public String suggestObjectLabel(BrowserTraceEventRecord event) {
        String pageTitle = event.pageTitle();
        if (pageTitle == null || pageTitle.isBlank()) {
            return null;
        }
        for (LoadedPack pack : activePacks(null)) {
            for (TraceRuleSetConfig.ObjectLabelRule rule : pack.config().objectLabelRules()) {
                if (rule.pageTitleContains() != null && pageTitle.contains(rule.pageTitleContains())) {
                    return rule.label();
                }
            }
        }
        return null;
    }

    @Override
    public String suggestDialogAction(BrowserTraceEventRecord event) {
        String text = event.elementText();
        String dialog = event.dialogTitle();
        if (text == null) text = "";
        if (dialog == null) dialog = "";
        for (LoadedPack pack : activePacks(null)) {
            for (TraceRuleSetConfig.DialogActionRule rule : pack.config().dialogActionRules()) {
                if (text.contains(defaultText(rule.elementTextContains(), ""))
                        && dialog.contains(defaultText(rule.dialogTitleContains(), ""))) {
                    return rule.action();
                }
            }
        }
        return null;
    }

    @Override
    public String suggestBusinessAction(BrowserTraceEventRecord event) {
        String text = event.elementText();
        String pageTitle = event.pageTitle();
        String dialogTitle = event.dialogTitle();
        String sectionTitle = event.sectionTitle();
        String ctx = (pageTitle != null ? pageTitle : "")
                + (dialogTitle != null ? dialogTitle : "")
                + (sectionTitle != null ? sectionTitle : "");

        for (LoadedPack pack : activePacks(null)) {
            for (TraceRuleSetConfig.BusinessActionRule rule : pack.config().businessActionRules()) {
                if (rule.contextContains() != null && !ctx.contains(rule.contextContains())) {
                    continue;
                }
                if (rule.exactText() != null && !Objects.equals(rule.exactText(), text)) {
                    continue;
                }
                if (rule.containsText() != null && (text == null || !text.toLowerCase().contains(rule.containsText().toLowerCase()))) {
                    continue;
                }
                return rule.action();
            }
        }
        return null;
    }

    @Override
    public String suggestCheckboxSemantics(BrowserTraceEventRecord event) {
        String text = sanitize(event.elementText());
        if (text == null || text.isBlank()) {
            return null;
        }
        for (LoadedPack pack : activePacks(null)) {
            for (TraceRuleSetConfig.CheckboxSemanticRule rule : TraceWorkflowSupport.safeList(pack.config().checkboxSemanticRules())) {
                String contains = defaultText(rule.elementTextContains(), "");
                if (!contains.isEmpty() && text.contains(contains)) {
                    return rule.semantic();
                }
            }
        }
        return null;
    }

    private List<LoadedPack> activePacks(Long projectId) {
        List<LoadedPack> result = new ArrayList<>();
        if (repository != null) {
            for (TraceRulePackConfigRepository.RulePackConfig pack : repository.loadActivePacks(projectId)) {
                if (pack.config() != null) {
                    result.add(new LoadedPack(
                            defaultText(pack.config().name(), defaultText(pack.name(), "database")),
                            pack.config()
                    ));
                }
            }
        }
        result.addAll(classpathPacks);
        if (repository == null) {
            return result;
        }
        return result;
    }

    private List<LoadedPack> loadClasspathPacks() {
        List<LoadedPack> result = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:trace-rulepacks/*.json");
            ObjectMapper mapper = new ObjectMapper();
            for (Resource resource : resources) {
                try (InputStream inputStream = resource.getInputStream()) {
                    TraceRuleSetConfig config = mapper.readValue(inputStream, TraceRuleSetConfig.class);
                    result.add(new LoadedPack(
                            defaultText(config.name(), resource.getFilename()),
                            config
                    ));
                } catch (Exception ignored) {
                    // ignore malformed packs to avoid blocking startup
                }
            }
        } catch (Exception ignored) {
            // ignore missing resources
        }
        return result;
    }

    private DescriptionDecision applyDescribeRules(TraceRuleSetConfig config,
                                                   List<TraceRuleSetConfig.DescribeRule> rules,
                                                   TraceDescriptionContext context) {
        if (rules == null || rules.isEmpty()) {
            return DescriptionDecision.pass();
        }
        for (TraceRuleSetConfig.DescribeRule rule : rules) {
            if (matchesRule(config, rule, context)) {
                if ("DROP".equalsIgnoreCase(defaultText(rule.resultType(), ""))) {
                    return DescriptionDecision.drop();
                }
                return DescriptionDecision.replace(renderTemplate(defaultText(rule.template(), ""), context));
            }
        }
        return DescriptionDecision.pass();
    }

    private boolean matchesRule(TraceRuleSetConfig config, TraceRuleSetConfig.DescribeRule rule, TraceDescriptionContext context) {
        String target = sanitize(context.target());
        String value = sanitize(context.value());
        String objectLabel = sanitize(context.objectLabel());
        String sectionTitle = sanitize(context.sectionTitle());
        String dialogTitle = sanitize(context.dialogTitle());
        String pageName = sanitize(context.pageName());
        String selectedObject = bestSelectedObject(objectLabel, target);

        if (rule.targetEquals() != null && !Objects.equals(rule.targetEquals(), target)) {
            return false;
        }
        if (rule.targetContains() != null && (target == null || !target.contains(rule.targetContains()))) {
            return false;
        }
        if (rule.valueEquals() != null && !Objects.equals(rule.valueEquals(), value)) {
            return false;
        }
        if (rule.pageNameContains() != null && (pageName == null || !pageName.contains(rule.pageNameContains()))) {
            return false;
        }
        if (rule.dialogTitleContains() != null && (dialogTitle == null || !dialogTitle.contains(rule.dialogTitleContains()))) {
            return false;
        }
        if (rule.sectionTitleContains() != null && (sectionTitle == null || !sectionTitle.contains(rule.sectionTitleContains()))) {
            return false;
        }
        if (Boolean.TRUE.equals(rule.targetEqualsValue()) && !Objects.equals(target, value)) {
            return false;
        }
        if (Boolean.TRUE.equals(rule.dialogContextRequired()) && !matchesDialogContext(sectionTitle, dialogTitle, pageName, config)) {
            return false;
        }
        if (Boolean.TRUE.equals(rule.objectSelectionContext())
                && !(inObjectSelectionContext(sectionTitle, dialogTitle, target, config) || looksLikeSelectedObject(target))) {
            return false;
        }
        if (Boolean.TRUE.equals(rule.selectedObjectRequired()) && (selectedObject == null || selectedObject.isBlank())) {
            return false;
        }
        return true;
    }

    private String renderTemplate(String template, TraceDescriptionContext context) {
        String target = defaultText(context.target(), "");
        String value = defaultText(context.value(), "");
        String selectedObject = defaultText(bestSelectedObject(context.objectLabel(), context.target()), "");
        return template
                .replace("${field}", target)
                .replace("${value}", value)
                .replace("${object}", selectedObject);
    }

    private String bestSelectedObject(String objectLabel, String fallbackTarget) {
        String candidate = sanitize(objectLabel);
        if (candidate != null && !candidate.isBlank()) {
            return collapseDuplicatedObject(candidate);
        }
        String fallback = sanitize(fallbackTarget);
        if (fallback == null || fallback.isBlank()) {
            return null;
        }
        if (fallback.contains(" · ")) {
            return collapseDuplicatedObject(fallback);
        }
        return null;
    }

    private String collapseDuplicatedObject(String text) {
        String cleaned = sanitize(text);
        if (cleaned == null) {
            return null;
        }
        String[] parts = cleaned.split("\\s*·\\s*");
        if (parts.length == 2) {
            String left = sanitize(parts[0]);
            String right = sanitize(parts[1]);
            if (Objects.equals(left, right)) {
                return left;
            }
            if (left != null && right != null) {
                if (left.matches("\\d{6,}") && !right.matches("\\d{6,}")) {
                    return right + " " + left;
                }
                if (right.matches("\\d{6,}") && !left.matches("\\d{6,}")) {
                    return left + " " + right;
                }
            }
        }
        return cleaned;
    }

    private boolean containsObjectHistoryContext(String sectionTitle, TraceRuleSetConfig config) {
        return containsAny(sectionTitle, config.objectHistoryContextContains());
    }

    private boolean matchesDialogContext(String sectionTitle, String dialogTitle, String pageName, TraceRuleSetConfig config) {
        return containsAny(sectionTitle, config.dialogContextContains())
                || containsAny(dialogTitle, config.dialogContextContains())
                || containsAny(pageName, config.dialogContextContains());
    }

    private boolean looksLikeSelectedObject(String text) {
        String cleaned = sanitize(text);
        if (cleaned == null || cleaned.isBlank()) {
            return false;
        }
        if (cleaned.contains(" · ")) {
            return true;
        }
        return cleaned.matches(".*\\d{6,}.*");
    }

    private boolean inObjectSelectionContext(String sectionTitle, String dialogTitle, String field, TraceRuleSetConfig config) {
        return containsObjectHistoryContext(sectionTitle, config)
                || containsAny(dialogTitle, config.objectSelectionDialogContains())
                || containsAny(field, config.objectSelectionTargetContains());
    }

    private boolean contains(List<String> candidates, String text) {
        return TraceWorkflowSupport.contains(candidates, text);
    }

    private boolean containsAny(String text, List<String> candidates) {
        return TraceWorkflowSupport.containsAny(text, candidates);
    }

    private boolean startsWithAny(String text, List<String> prefixes) {
        return TraceWorkflowSupport.startsWithAny(text, prefixes);
    }

    private boolean matchesAnyRegex(String text, List<String> regexes) {
        return TraceWorkflowSupport.matchesAnyRegex(text, regexes);
    }

    private String rewriteChoice(String desc, List<String> descriptions, List<String> rewrites) {
        return TraceWorkflowSupport.rewriteChoice(desc, descriptions, rewrites);
    }

    private <T> List<T> safeList(List<T> values) {
        return TraceWorkflowSupport.safeList(values);
    }

    private int defaultInt(Integer value, int fallback) {
        return TraceWorkflowSupport.defaultInt(value, fallback);
    }

    private String sanitize(String text) {
        return TraceWorkflowSupport.sanitize(text);
    }

    private String defaultText(String text, String fallback) {
        return TraceWorkflowSupport.defaultText(text, fallback);
    }

    private record LoadedPack(String name, TraceRuleSetConfig config) {
    }
}
