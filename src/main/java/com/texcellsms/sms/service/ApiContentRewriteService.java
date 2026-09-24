package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.webportal.ApiContentRewriteMapper;
import com.texcellsms.sms.model.DTO.ApiContentRewriteSaveResult;
import com.texcellsms.sms.model.entity.ApiContentRewrite;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
@RequiredArgsConstructor
public class ApiContentRewriteService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 1000;

    private final ApiContentRewriteMapper apiContentRewriteMapper;

    public Map<String, Object> query(String account, String ruleName, String mcc, String mnc,
                                     Integer enabled, Integer onlyEnabled, Integer pageNum, Integer pageSize) {
        validateAccount(account);
        int safePageNum = normalizePageNum(pageNum);
        int safePageSize = normalizePageSize(pageSize);
        Integer enabledFilter = enabled != null ? enabled : onlyEnabled;
        if (enabledFilter != null) {
            validateSwitch("enabled", enabledFilter);
        }

        String safeRuleName = trimToNull(ruleName);
        String safeMcc = trimToNull(mcc);
        String safeMnc = trimToNull(mnc);
        int offset = (safePageNum - 1) * safePageSize;

        List<ApiContentRewrite> records = apiContentRewriteMapper.selectPage(
                offset, safePageSize, safeRuleName, safeMcc, safeMnc, account, enabledFilter);
        long total = apiContentRewriteMapper.countForPage(
                safeRuleName, safeMcc, safeMnc, account, enabledFilter);

        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("list", records);
        result.put("total", total);
        result.put("pageNum", safePageNum);
        result.put("pageSize", safePageSize);
        result.put("totalPages", total == 0 ? 0 : (total + safePageSize - 1) / safePageSize);
        return result;
    }

    public ApiContentRewriteSaveResult add(String account, ApiContentRewrite rule) {
        validateAccount(account);
        if (rule != null) {
            rule.setSubAccount(account);
        }
        prepareAndValidate(rule, false);
        return apiContentRewriteMapper.add(rule);
    }

    @Transactional(transactionManager = "webPortalTransactionManager")
    public ApiContentRewriteSaveResult update(String account, ApiContentRewrite rule) {
        validateAccount(account);
        if (rule != null) {
            rule.setSubAccount(account);
        }
        prepareAndValidate(rule, true);
        verifyOwnedRule(account, rule.getUnid());
        return apiContentRewriteMapper.update(rule);
    }

    @Transactional(transactionManager = "webPortalTransactionManager")
    public ApiContentRewriteSaveResult delete(String account, Integer unid) {
        validateAccount(account);
        if (unid == null || unid <= 0) {
            throw new IllegalArgumentException("UNID is required");
        }
        verifyOwnedRule(account, unid);
        return apiContentRewriteMapper.delete(unid);
    }

    private void verifyOwnedRule(String account, Integer unid) {
        String owner = apiContentRewriteMapper.selectOwnerForUpdate(unid);
        if (!account.equals(owner)) {
            throw new IllegalArgumentException("Rule not found for current account");
        }
    }

    private void validateAccount(String account) {
        if (account == null || account.trim().isEmpty()) {
            throw new IllegalArgumentException("Current account is unavailable");
        }
    }

    private void prepareAndValidate(ApiContentRewrite rule, boolean update) {
        if (rule == null) {
            throw new IllegalArgumentException("request body cannot be empty");
        }
        if (update && (rule.getUnid() == null || rule.getUnid() <= 0)) {
            throw new IllegalArgumentException("UNID is required");
        }

        rule.setRuleName(trimToEmpty(rule.getRuleName()));
        rule.setMcc(trimToEmpty(rule.getMcc()));
        rule.setMnc(trimToEmpty(rule.getMnc()));
        rule.setSubAccount(trimToEmpty(rule.getSubAccount()));
        rule.setMatchPattern(nullToEmpty(rule.getMatchPattern()));
        rule.setReplaceTemplate(nullToEmpty(rule.getReplaceTemplate()));
        rule.setRemark(trimToEmpty(rule.getRemark()));

        if (rule.getPriority() == null) {
            rule.setPriority(100);
        }
        if (rule.getIgnoreCase() == null) {
            rule.setIgnoreCase(1);
        }
        if (rule.getGlobalReplace() == null) {
            rule.setGlobalReplace(1);
        }
        if (rule.getStopOnMatch() == null) {
            rule.setStopOnMatch(1);
        }
        if (rule.getRandNoRepeat() == null) {
            rule.setRandNoRepeat(0);
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(1);
        }

        validateScope("MCC", rule.getMcc(), 8);
        validateScope("MNC", rule.getMnc(), 8);
        validateLength("RuleName", rule.getRuleName(), 64);
        validateLength("SubAccount", rule.getSubAccount(), 32);
        validateLength("MatchPattern", rule.getMatchPattern(), 512);
        validateLength("ReplaceTemplate", rule.getReplaceTemplate(), 512);
        validateLength("Remark", rule.getRemark(), 255);
        validateSwitch("ignoreCase", rule.getIgnoreCase());
        validateSwitch("globalReplace", rule.getGlobalReplace());
        validateSwitch("stopOnMatch", rule.getStopOnMatch());
        validateSwitch("randNoRepeat", rule.getRandNoRepeat());
        validateSwitch("enabled", rule.getEnabled());
        validatePattern(rule.getMatchPattern());
        validateTemplate(rule.getReplaceTemplate(), countCaptureGroups(rule.getMatchPattern()), rule.getRandNoRepeat());
    }

    private void validatePattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("MatchPattern cannot be empty");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("MatchPattern syntax error: " + e.getDescription());
        }
        if (containsUnsupportedGroupSyntax(pattern)) {
            throw new IllegalArgumentException("MatchPattern contains unsupported ECMAScript group syntax");
        }
        if (containsUnsupportedEscape(pattern)) {
            throw new IllegalArgumentException("MatchPattern contains unsupported escape syntax");
        }
        if (hasNestedUnboundedQuantifier(pattern)) {
            throw new IllegalArgumentException("MatchPattern contains nested unbounded quantifiers");
        }
    }

    private void validateTemplate(String template, int groupCount, Integer randNoRepeat) {
        Map<Integer, Integer> randCounts = new HashMap<>();
        for (int i = 0; i < template.length(); ) {
            char ch = template.charAt(i);
            if (ch != '$') {
                i++;
                continue;
            }
            if (i + 1 >= template.length()) {
                i++;
                continue;
            }

            char next = template.charAt(i + 1);
            if (next == '$' || next == '&') {
                i += 2;
                continue;
            }
            if (Character.isDigit(next)) {
                i = validateTemplateGroupRef(template, i, groupCount);
                continue;
            }
            if (next == '{') {
                int end = template.indexOf('}', i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("ReplaceTemplate has '${' without matching '}'");
                }
                String token = template.substring(i + 2, end).trim();
                validateTemplateFunction(token, groupCount, randCounts);
                i = end + 1;
                continue;
            }
            if (isAsciiLetter(next)) {
                throw new IllegalArgumentException("ReplaceTemplate has invalid '$' variable");
            }
            i++;
        }

        if (randNoRepeat != null && randNoRepeat == 1) {
            for (Map.Entry<Integer, Integer> entry : randCounts.entrySet()) {
                int digits = entry.getKey();
                int count = entry.getValue();
                if (digits <= 9 && count > (int) Math.pow(10, digits)) {
                    throw new IllegalArgumentException("RandNoRepeat cannot satisfy RAND:" + digits);
                }
            }
        }
    }

    private int validateTemplateGroupRef(String template, int dollarIndex, int groupCount) {
        int firstStart = dollarIndex + 1;
        int first = template.charAt(firstStart) - '0';
        if (first == 0) {
            return firstStart + 1;
        }

        if (firstStart + 1 < template.length() && Character.isDigit(template.charAt(firstStart + 1))) {
            int two = Integer.parseInt(template.substring(firstStart, firstStart + 2));
            if (two >= 1 && two <= groupCount) {
                return firstStart + 2;
            }
        }

        if (first > groupCount) {
            throw new IllegalArgumentException("ReplaceTemplate references non-existing group $" + first);
        }
        return firstStart + 1;
    }

    private void validateTemplateFunction(String token, int groupCount, Map<Integer, Integer> randCounts) {
        String compact = token.replace(" ", "");
        String upper = compact.toUpperCase();
        if (upper.startsWith("RAND:")) {
            int digits = parseRequiredInt(compact.substring(5), "RAND digits");
            if (digits < 1 || digits > 16) {
                throw new IllegalArgumentException("RAND digits must be between 1 and 16");
            }
            randCounts.put(digits, randCounts.getOrDefault(digits, 0) + 1);
            return;
        }

        if (upper.startsWith("SUB:")) {
            String[] parts = compact.substring(4).split(",", -1);
            if (parts.length != 2 && parts.length != 3) {
                throw new IllegalArgumentException("SUB requires 2 or 3 parameters");
            }
            int group = parseRequiredInt(parts[0], "SUB group");
            parseRequiredInt(parts[1], "SUB start");
            if (group < 0 || group > groupCount) {
                throw new IllegalArgumentException("SUB references non-existing group " + group);
            }
            if (parts.length == 3 && parseRequiredInt(parts[2], "SUB length") <= 0) {
                throw new IllegalArgumentException("SUB length must be greater than 0");
            }
            return;
        }

        throw new IllegalArgumentException("ReplaceTemplate contains unsupported function: " + token);
    }

    private int countCaptureGroups(String pattern) {
        int count = 0;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); ) {
            char ch = pattern.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (inClass) {
                if (ch == ']') {
                    inClass = false;
                }
                i++;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                i++;
                continue;
            }
            if (ch == '(') {
                if (i + 1 >= pattern.length() || pattern.charAt(i + 1) != '?') {
                    count++;
                }
                i++;
                continue;
            }
            i++;
        }
        return count;
    }

    private boolean hasNestedUnboundedQuantifier(String regex) {
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        boolean inClass = false;
        for (int i = 0; i < regex.length(); ) {
            char ch = regex.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (inClass) {
                if (ch == ']') {
                    inClass = false;
                }
                i++;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                i++;
                continue;
            }
            if (ch == '(') {
                stack.push(i);
                i++;
                continue;
            }
            if (ch == ')') {
                Integer open = stack.poll();
                if (open != null && unboundedQuantifierLength(regex, i + 1) > 0
                        && fragmentHasUnboundedQuantifier(regex, open + 1, i)) {
                    return true;
                }
                i++;
                continue;
            }
            i++;
        }
        return false;
    }

    private boolean fragmentHasUnboundedQuantifier(String regex, int start, int end) {
        boolean inClass = false;
        for (int i = start; i < end; ) {
            char ch = regex.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (inClass) {
                if (ch == ']') {
                    inClass = false;
                }
                i++;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                i++;
                continue;
            }
            int quantifierLength = unboundedQuantifierLength(regex, i);
            if (quantifierLength > 0) {
                return true;
            }
            i++;
        }
        return false;
    }

    private int unboundedQuantifierLength(String regex, int index) {
        if (index >= regex.length()) {
            return 0;
        }
        char ch = regex.charAt(index);
        if (ch == '*' || ch == '+') {
            return 1;
        }
        if (ch != '{') {
            return 0;
        }
        int end = regex.indexOf('}', index);
        if (end < 0) {
            return 0;
        }
        String body = regex.substring(index + 1, end);
        int comma = body.indexOf(',');
        return comma >= 0 && comma == body.length() - 1 ? end - index + 1 : 0;
    }

    private boolean containsUnsupportedGroupSyntax(String pattern) {
        boolean inClass = false;
        for (int i = 0; i < pattern.length() - 2; ) {
            char ch = pattern.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (inClass) {
                if (ch == ']') {
                    inClass = false;
                }
                i++;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                i++;
                continue;
            }
            if (ch == '(' && pattern.charAt(i + 1) == '?') {
                char type = pattern.charAt(i + 2);
                if (type != ':' && type != '=' && type != '!') {
                    return true;
                }
            }
            i++;
        }
        return false;
    }

    private boolean containsUnsupportedEscape(String pattern) {
        return pattern.contains("\\p{") || pattern.contains("\\P{") || pattern.contains("\\x{");
    }

    private void validateScope(String fieldName, String value, int maxBytes) {
        validateLength(fieldName, value, maxBytes);
        if (value.isEmpty() || "*".equals(value)) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                throw new IllegalArgumentException(fieldName + " must be empty, '*', or digits");
            }
        }
    }

    private void validateLength(String fieldName, String value, int maxBytes) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(fieldName + " length cannot exceed " + maxBytes + " bytes");
        }
    }

    private void validateSwitch(String fieldName, Integer value) {
        if (value == null || (value != 0 && value != 1)) {
            throw new IllegalArgumentException(fieldName + " must be 0 or 1");
        }
    }

    private int parseRequiredInt(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be an integer");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " must be an integer");
        }
    }

    private boolean isAsciiLetter(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
