package org.mobileagent.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalPreprocessor {
    static final int ACTION_PASS = 0;
    static final int ACTION_REDACT = 1;
    static final int ACTION_BLOCK = 2;
    static final int ACTION_LOCAL_REPLY = 3;
    static final int ACTION_CONFIRM = 4;

    static final class Result {
        final int action;
        final String text;
        final String reply;
        final List<String> findings;
        final boolean modelUsed;
        final String kind;

        Result(int action, String text, String reply, List<String> findings,
               boolean modelUsed, String kind) {
            this.action = action;
            this.text = text;
            this.reply = reply;
            this.findings = findings == null ? new ArrayList<String>() : findings;
            this.modelUsed = modelUsed;
            this.kind = kind == null ? "" : kind;
        }

        static Result pass(String text) {
            return new Result(ACTION_PASS, text, "", null, false, "none");
        }
    }

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)\\b(sk-[a-z0-9_-]{12,}|ghp_[a-z0-9]{20,}|github_pat_[a-z0-9_]{20,}"
                    + "|akia[0-9a-z]{16}|xox[baprs]-[a-z0-9-]{10,})\\b");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern PASSWORD = Pattern.compile(
            "(?i)(Password|Password|password|passwd|pwd)(\\s*(?:Yes.|Yes|:|: |=)\\s*)([^\\s, . ; ;]{4,})");
    private static final Pattern EMAIL_CONTEXT = Pattern.compile(
            "(?i)(Mailbox|email|e-mail)(\\s*(?:Yes.|Yes|:|: |=)\\s*)"
                    + "([a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})");
    private static final Pattern CN_ID = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private static final String GREETING =
            "Hello, I am.CRLM. Can read and write workspace, execute commands, build or install APK, "
                    + "You can also plan, redisclose and sequester after you have been authorized to operate the cell phone interface.";
    private static final String CAPABILITIES =
            "I can read and write workspace files, search codes, execute orders, build and install APK, The Internet is a tool for the development of the Internet."
                    + "The information is sensitive and is sensitive to local problems."
                    + "The system level operation that cannot be completed is described locally.";
    private static final String UNSAFE =
            "This operation is beyond the current one.CRLM Permission: Equipment not available root, I can't brush the machine. I can't unlock it. Bootloader, "
                    + "Private data that are identified, recovered from the plant or read out of other applications around the system installation."
                    + "I can open the system settings, check access, or switch to the public. API Finish what you can do.";
    private static final String DATA_LOSS =
            "The recovery of the plant, formatting or removal of data could result in irreversible data loss,App It won't be executed."
                    + "Please backup and then manual in the system settings.";
    private static final String INSTALL_GUARD =
            "Android The project is being conducted by the Ministry of Health and Social Welfare, which is responsible for the implementation of the project.App No bypass or silent installation can be carried out."
                    + "I can build and submit the installation, confirm the bullet window by you clicking.";
    private static final String EXTERNAL_ACTION =
            "Current Agent No payment, transfer, proxy or texting tools, and no bypassing. App Restrictions."
                    + "Please respond to the other side. App I'll do it manually, or I'll help you with the text.";
    private static final String PRIVATE_DATA =
            "Private databases, cracking, burglary or bypassing lock screens for other applications cannot be directly accessed."
                    + "Enable 'if you just want to see the chat on the current screen 'CRLM The Internet is a tool for the development of the Internet."
                    + "I can read the current Visible Interface; protected interfaces and private data are still inaccessible.";
    private static final String SYSTEM_APP =
            "The system will not be unloaded or the core system services will be disabled, which may result in equipment not being available."
                    + "If you want to clean up the space, I can check the workspace files and the temporary files that can be deleted.";

    private static final String[][] DANGER_RULES = {
            {"(Access|Got it.|Yes.|Yes.|Yes.|Take it.|Open)\\s*(root|Super Permissions)|root\\s*(Cell phone.|Equipment|Permissions)|"
                    + "(?i)\\b(get|gain|obtain|need|want)\\s+root\\b|"
                    + "(?i)\\broot\\s+(the\\s+)?(phone|device|android)\\b|"
                    + "(?i)help\\s+me\\s+root|magisk|Brusher|Wire|- The swipe.|Prison break|"
                    + "Break\\s*bl\\s*Lock|Unlock\\s*bootloader|(?i)\\bflash\\s+(rom|firmware|bootloader)\\b",
                    UNSAFE},
            {"Recover the factory.|Recover to a new machine.|Restore Initial Status|Reset Cell|Empty your cell phone.|Erase everything.|"
                    + "Formatting\\s*(Cell phone.|Storage|Disk|Hard Disk|Data)|Clear All Data|Clear all data|"
                    + "factory\\s*reset|factory\\s*state|wipe\\s*(data|this\\s+phone|the\\s+phone)|"
                    + "reset\\s+(this\\s+)?(phone|device).{0,30}(factory|data)|"
                    + "(?i)\\b(delete|erase|format|wipe)\\s+(the\\s+)?(entire|whole|all)\\s+"
                    + "(disk|storage|data|phone)\\b|Deleting|rm\\s+-rf\\s+/",
                    DATA_LOSS},
            {"Silent installation|Around.*(Installation confirmation|Install Verification|System Installer)|Close.*Installation confirmation|"
                    + "silent\\s*install|bypass.*install",
                    INSTALL_GUARD},
            {"(Help me.|Give it to me.|Direct|Now.|Immediately|Take my place.).{0,8}(Payments|Transfers|Payments|Order.|Purchase)|"
                    + "(Payments|Transfers|Payments).{0,6}(Here.|In|Present.|Dollar|Blocks|Money.)|"
                    + "Send Message to|Sending messages|Send a text.|(Help me.|Give it to me.|Take my place.).{0,8}(Sending a message.|Send Message|Text)|"
                    + "(?i)\\b(send|post)\\s+(a\\s+)?(message|sms|text)\\s+(to|for)\\b",
                    EXTERNAL_ACTION},
            {"Read.{0,8}(Wess.|Chat Record|Other applications|Someone else.|Other).{0,8}(Data|Contents|Records|Message)|"
                    + "Crack|The bandit.|Around the lock screen|Go around the password.|Steal|(?i)\\bddos\\b|Attacking websites|"
                    + "(?i)\\b(read|access|extract|steal)\\s+(my\\s+)?(wechat|chat|private|other)\\b"
                    + ".{0,20}(data|messages|history)\\b",
                    PRIVATE_DATA},
            {"Unmount System Application|Disable System Service|Remove System Application|Disable system application|Modify System Partition",
                    SYSTEM_APP}
    };

    private static final Pattern QUESTION = Pattern.compile(
            "- You're not?[??]?$|And?[??]?$|Can you...|Can you...|Is that okay?|Is it possible?|Why?|What?|How's that?|Can I?");
    private static final Pattern IMPERATIVE = Pattern.compile(
            "Help me.|Give it to me.|Direct|Now.|Immediately|Go on.|Implementation|Operation|Done.|Do something.|Take my place.|Get it for me.|Now.");
    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(Hello.|Hello.|hi|hello|Hey.|Hello?|Hello?|Thank you.|Thank you.|thanks|thank\\s*you|Good work.)"
                    + "[!!. .,, \\s]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPABILITY_PATTERN = Pattern.compile(
            "What can you do?|What would you do?|What's the function?|What can you do?|Help|How?|Description of use|Introduce yourself.");

    private LocalPreprocessor() {
    }

    static Result rulesOnly(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return new Result(ACTION_BLOCK, text, "Please enter a specific task.", null, false, "empty");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (GREETING_PATTERN.matcher(lower).matches()) {
            return new Result(ACTION_LOCAL_REPLY, text, GREETING, null, false, "trivial");
        }
        if (text.length() <= 80 && CAPABILITY_PATTERN.matcher(text).find()) {
            return new Result(ACTION_LOCAL_REPLY, text, CAPABILITIES, null, false, "trivial");
        }

        for (String[] rule : DANGER_RULES) {
            Pattern pattern = Pattern.compile(rule[0], Pattern.CASE_INSENSITIVE);
            if (!pattern.matcher(text).find()) {
                continue;
            }
            boolean question = QUESTION.matcher(text).find()
                    && !IMPERATIVE.matcher(text).find();
            return new Result(question ? ACTION_LOCAL_REPLY : ACTION_BLOCK,
                    text, rule[1] + "\n\nIf you just want to understand why, you can keep asking me.",
                    null, false, "impossible");
        }

        Sensitive sensitive = redact(text);
        if (!sensitive.findings.isEmpty()) {
            return new Result(ACTION_REDACT, sensitive.text,
                    "Sensitive information detected, dissensitized locally; original content is not uploaded.",
                    sensitive.findings, false, "sensitive");
        }
        return Result.pass(text);
    }

    static Result applyModelHint(Result base, LocalAi.ClassifyResult model) {
        if (base == null || model == null || base.action != ACTION_PASS) {
            return base;
        }
        String kind = model.kind == null ? "" : model.kind.toLowerCase(Locale.ROOT);
        if ("impossible".equals(kind)) {
            return new Result(ACTION_CONFIRM, base.text,
                    "Local models determine that this may be an uncompleted or high-risk operation:"
                            + safeNote(model.note),
                    null, true, kind);
        }
        if ("sensitive".equals(kind)) {
            return new Result(ACTION_CONFIRM, base.text,
                    "Local models think that messages may contain sensitive information:" + safeNote(model.note)
                            + ". Please change or confirm before sending.",
                    null, true, kind);
        }
        return new Result(base.action, base.text, base.reply, base.findings, true, kind);
    }

    private static String safeNote(String note) {
        String value = note == null ? "" : note.trim();
        if (value.isEmpty()) {
            return "No specific reasons provided";
        }
        return value.length() <= 80 ? value : value.substring(0, 80) + "...";
    }

    static final class Sensitive {
        final String text;
        final List<String> findings;

        Sensitive(String text, List<String> findings) {
            this.text = text;
            this.findings = findings;
        }
    }

    /** Reuses the same secret redaction on an explicit guard override; policy can be waived, secrets cannot. */
    static Sensitive redactForOverride(String input) {
        return redact(input == null ? "" : input.trim());
    }

    private static Sensitive redact(String input) {
        String text = input;
        List<String> findings = new ArrayList<>();
        text = replace(text, PRIVATE_KEY, "[De-sensitized.:Private Key]", findings, "Private Key");
        text = replace(text, API_KEY, "[De-sensitized.:API Key]", findings, "API Key");
        text = replace(text, JWT, "[De-sensitized.:token]", findings, "token");
        text = replacePassword(text, findings);
        text = replaceEmail(text, findings);
        text = replaceCheckedId(text, findings);
        text = replaceCheckedCard(text, findings);
        text = replace(text, PHONE, "[De-sensitized.:Cell phone number]", findings, "Cell phone number");
        return new Sensitive(text, dedupe(findings));
    }

    private static String replace(String text, Pattern pattern, String replacement,
                                  List<String> findings, String label) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
            count++;
        }
        matcher.appendTail(buffer);
        if (count > 0) {
            findings.add(label + " ×" + count);
        }
        return buffer.toString();
    }

    private static String replacePassword(String text, List<String> findings) {
        Matcher matcher = PASSWORD.matcher(text);
        int count = 0;
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + "[De-sensitized.:Password]"));
            count++;
        }
        matcher.appendTail(buffer);
        if (count > 0) {
            findings.add("Password ×" + count);
        }
        return buffer.toString();
    }

    private static String replaceEmail(String text, List<String> findings) {
        Matcher matcher = EMAIL_CONTEXT.matcher(text);
        int count = 0;
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + "[De-sensitized.:Mailbox]"));
            count++;
        }
        matcher.appendTail(buffer);
        if (count > 0) {
            findings.add("Mailbox ×" + count);
        }
        return buffer.toString();
    }

    private static String replaceCheckedId(String text, List<String> findings) {
        Matcher matcher = CN_ID.matcher(text);
        int count = 0;
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            if (!validChineseId(matcher.group())) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("[De-sensitized.:ID card]"));
            count++;
        }
        matcher.appendTail(buffer);
        if (count > 0) {
            findings.add("ID card ×" + count);
        }
        return buffer.toString();
    }

    private static String replaceCheckedCard(String text, List<String> findings) {
        Matcher matcher = CARD.matcher(text);
        int count = 0;
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[^0-9]", "");
            if (digits.length() < 13 || digits.length() > 19 || !luhn(digits)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("[De-sensitized.:Bank card]"));
            count++;
        }
        matcher.appendTail(buffer);
        if (count > 0) {
            findings.add("Bank card ×" + count);
        }
        return buffer.toString();
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int value = digits.charAt(i) - '0';
            if (doubleDigit) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            sum += value;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private static boolean validChineseId(String id) {
        if (id.length() != 18) {
            return false;
        }
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] checks = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * weights[i];
        }
        char expected = checks[sum % 11];
        char actual = Character.toUpperCase(id.charAt(17));
        return expected == actual;
    }

    private static List<String> dedupe(List<String> values) {
        Set<String> seen = new LinkedHashSet<>(values);
        return new ArrayList<>(seen);
    }
}
