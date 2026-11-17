package com.company.aitest.minitom;

import com.company.aitest.common.BusinessException;
import com.company.aitest.common.CurrentUser;
import com.company.aitest.common.TimeProvider;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Preserves original human-authored cases, writes formal cases, and produces reviewable TOM evidence. */
@Service
public class HistoricalCaseImportService {
    private static final int MAX_IMPORT_ITEMS = 2_000;
    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private final JdbcTemplate jdbcTemplate;
    private final TimeProvider timeProvider;
    private final HistoricalCaseTomCandidateService candidateService;

    public HistoricalCaseImportService(JdbcTemplate jdbcTemplate, TimeProvider timeProvider,
                                       HistoricalCaseTomCandidateService candidateService) {
        this.jdbcTemplate = jdbcTemplate;
        this.timeProvider = timeProvider;
        this.candidateService = candidateService;
    }

    public record CaseInput(String title, String module, String precondition, String steps,
                            String expectedResult, String priority, String caseType, String designMethod) {}
    public record ImportCommand(Long projectId, String batchName, String businessDomain, List<CaseInput> cases) {}
    public record ImportBatchView(Long id, Long projectId, String batchName, String businessDomain,
                                  String sourceFormat, int itemCount, int eligibleCount, String status,
                                  LocalDateTime createdAt) {}
    public record ImportResult(Long importId, int imported, int formalImported, int eligible, int skipped,
                               HistoricalCaseTomCandidateService.ExtractionResult extraction) {}

    @Transactional
    public ImportResult importCases(ImportCommand command, String sourceFormat, CurrentUser user) {
        if (command == null || command.projectId() == null) throw new BusinessException("项目不能为空");
        List<CaseInput> cases = command.cases() == null ? List.of() : command.cases().stream()
                .filter(c -> c != null && !blank(c.title())).toList();
        if (cases.isEmpty()) throw new BusinessException("至少需要一条包含用例标题的历史用例");
        if (cases.size() > MAX_IMPORT_ITEMS) throw new BusinessException("单次最多导入 " + MAX_IMPORT_ITEMS + " 条历史用例");
        LocalDateTime now = timeProvider.now();
        long actor = user == null || user.id() == null ? 0L : user.id();
        String batchName = blank(command.batchName()) ? "历史用例导入 " + now : clip(command.batchName(), 255);
        jdbcTemplate.update("""
                INSERT INTO historical_case_import(project_id, batch_name, business_domain, source_format, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, command.projectId(), batchName, clip(command.businessDomain(), 128), sourceFormat, actor, now, now);
        Long importId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String projectName = jdbcTemplate.queryForObject("SELECT project_name FROM project WHERE id = ?", String.class, command.projectId());
        int eligible = 0;
        int skipped = 0;
        int imported = 0;
        List<Long> eligibleIds = new ArrayList<>();
        Set<String> importedHashes = new HashSet<>();
        int sequence = 0;
        for (CaseInput input : cases) {
            String hash = sha256(join(input.title(), input.module(), input.precondition(), input.steps(), input.expectedResult(), input.priority(), input.caseType(), input.designMethod()));
            if (!importedHashes.add(hash)) {
                skipped++;
                continue;
            }
            sequence++;
            boolean allowed = HistoricalCaseTomCandidateService.isEligibleForExtraction(input.title(), input.module(), input.precondition(), input.steps(), input.expectedResult());
            jdbcTemplate.update("""
                    INSERT INTO historical_case_import_item(import_id, project_id, sequence_no, case_title, module_name,
                        precondition, steps, expected_result, priority, case_type, design_method, source_hash, eligibility_status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, importId, command.projectId(), sequence, clip(input.title(), 512), clip(input.module(), 255),
                    input.precondition(), input.steps(), input.expectedResult(), clip(input.priority(), 32),
                    clip(input.caseType(), 64), clip(input.designMethod(), 128), hash, allowed ? "ELIGIBLE" : "SKIPPED", now);
            Long itemId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbcTemplate.update("""
                    INSERT INTO test_case_asset(project_id, source_task_id, source_draft_id, case_no, case_title,
                        project_name, module_name, precondition, steps, expected_result, priority, case_type,
                        scenario_type, design_method, source_refs_json, created_at, updated_at, case_scope,
                        case_status, submitted_by, submitted_at, import_source_type, import_source_ref_id)
                    VALUES (?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSITIVE', ?, ?, ?, ?,
                        'PROJECT', 'SUBMITTED', ?, ?, 'MANUAL_IMPORT', ?)
                    """, command.projectId(), "IMPORT-" + itemId, clip(input.title(), 255), projectName,
                    clip(input.module(), 255), input.precondition(), input.steps(), input.expectedResult(),
                    clip(input.priority(), 16), clip(input.caseType(), 64), clip(input.designMethod(), 64),
                    "{\"sourceType\":\"MANUAL_IMPORT\",\"historicalImportId\":" + importId
                        + ",\"historicalImportItemId\":" + itemId + "}", now, now, actor, now, itemId);
            imported++;
            if (allowed) { eligible++; eligibleIds.add(itemId); } else skipped++;
        }
        jdbcTemplate.update("UPDATE historical_case_import SET item_count = ?, eligible_count = ?, updated_at = ? WHERE id = ?",
                imported, eligible, now, importId);
        HistoricalCaseTomCandidateService.ExtractionResult total = new HistoricalCaseTomCandidateService.ExtractionResult(0, 0, 0, 0);
        for (Long id : eligibleIds) total = plus(total, candidateService.extractImportedItem(command.projectId(), id, user));
        return new ImportResult(importId, imported, imported, eligible, skipped, total);
    }

    @Transactional(readOnly = true)
    public List<ImportBatchView> list(Long projectId) {
        return jdbcTemplate.query("""
                SELECT id, project_id, batch_name, business_domain, source_format, item_count, eligible_count, status, created_at
                FROM historical_case_import WHERE project_id = ? ORDER BY created_at DESC, id DESC
                """, (rs, i) -> new ImportBatchView(rs.getLong("id"), rs.getLong("project_id"), rs.getString("batch_name"),
                rs.getString("business_domain"), rs.getString("source_format"), rs.getInt("item_count"),
                rs.getInt("eligible_count"), rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime()), projectId);
    }

    public ImportResult importFile(Long projectId, String batchName, String businessDomain, MultipartFile file, CurrentUser user) throws IOException {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择 Excel 或 CSV 文件");
        if (file.getSize() > MAX_UPLOAD_BYTES) throw new BusinessException("历史用例文件不能超过 20MB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        List<CaseInput> cases;
        String format;
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) { cases = parseWorkbook(file); format = "EXCEL"; }
        else if (name.endsWith(".csv")) { cases = parseCsv(new String(file.getBytes(), StandardCharsets.UTF_8)); format = "CSV"; }
        else throw new BusinessException("仅支持 .xlsx、.xls 或 .csv 历史用例文件");
        return importCases(new ImportCommand(projectId, blank(batchName) ? file.getOriginalFilename() : batchName, businessDomain, cases), format, user);
    }

    static List<CaseInput> parseCsv(String text) {
        List<String> lines = text == null ? List.of() : text.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() < 2) throw new BusinessException("CSV 至少需要表头和一条用例");
        List<String> headers = csvLine(lines.get(0));
        List<CaseInput> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) result.add(fromValues(headers, csvLine(lines.get(i))));
        return result;
    }

    private List<CaseInput> parseWorkbook(MultipartFile file) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            var sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) throw new BusinessException("Excel 至少需要表头和一条用例");
            DataFormatter formatter = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < header.getLastCellNum(); c++) headers.add(formatter.formatCellValue(header.getCell(c)));
            List<CaseInput> result = new ArrayList<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r); if (row == null) continue;
                List<String> values = new ArrayList<>();
                for (int c = 0; c < headers.size(); c++) values.add(formatter.formatCellValue(row.getCell(c)));
                CaseInput input = fromValues(headers, values);
                if (!blank(input.title())) result.add(input);
            }
            return result;
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("无法读取历史用例文件，请使用首个工作表并保留表头"); }
    }

    private static CaseInput fromValues(List<String> headers, List<String> values) {
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) data.put(normalizeHeader(headers.get(i)), i < values.size() ? values.get(i).trim() : "");
        return new CaseInput(value(data, "用例标题", "标题", "case_title", "title"), value(data, "模块", "模块名称", "module", "module_name"),
                value(data, "前置条件", "precondition"), value(data, "步骤", "操作步骤", "steps"),
                value(data, "预期结果", "期望结果", "expected_result", "expected"), value(data, "优先级", "priority"),
                value(data, "用例类型", "case_type", "type"), value(data, "设计方法", "design_method"));
    }
    private static String value(Map<String, String> data, String... keys) { for (String key : keys) { String value = data.get(normalizeHeader(key)); if (value != null) return value; } return ""; }
    private static String normalizeHeader(String value) { return value == null ? "" : value.replace("\uFEFF", "").replace(" ", "").replace("_", "").toLowerCase(Locale.ROOT); }
    private static List<String> csvLine(String line) { List<String> cells = new ArrayList<>(); StringBuilder cell = new StringBuilder(); boolean quoted = false; for (int i = 0; i < line.length(); i++) { char ch = line.charAt(i); if (ch == '"') { if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append(ch); i++; } else quoted = !quoted; } else if (ch == ',' && !quoted) { cells.add(cell.toString().trim()); cell.setLength(0); } else cell.append(ch); } cells.add(cell.toString().trim()); return cells; }
    private HistoricalCaseTomCandidateService.ExtractionResult plus(HistoricalCaseTomCandidateService.ExtractionResult a, HistoricalCaseTomCandidateService.ExtractionResult b) { return new HistoricalCaseTomCandidateService.ExtractionResult(a.candidates() + b.candidates(), a.relations() + b.relations(), a.unchanged() + b.unchanged(), a.skipped() + b.skipped()); }
    private String sha256(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String join(String... values) { return String.join("\n", java.util.Arrays.stream(values).map(v -> v == null ? "" : v).toList()); }
    private static String clip(String value, int max) { if (value == null) return null; return value.length() <= max ? value : value.substring(0, max); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
