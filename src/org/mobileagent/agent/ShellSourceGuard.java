package org.mobileagent.agent;

import java.util.Locale;

/**
 * run/shell Source reading and writing guard.
 *
 * <p>The rules are only for'Read/ Write Source Files'Order: in the same paragraph .java/.kt/.xml The only way to stop is by the same source path.
 * The tail of the pipe. {@code head -20}/{@code tail -n 30} Just cut the output, not read in source code, not wrongly.
 * Otherwise... {@code grep ... Foo.xml | head -20} Such normal downscaling orders are blocked by error.</p>
 */
final class ShellSourceGuard {

    private ShellSourceGuard() {
    }

    static String check(String command) {
        if (command == null || command.trim().isEmpty()) {
            return null;
        }
        if (!mentionsSource(command.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String[] segments = command.split("&&|;|\\|");
        for (String segment : segments) {
            String s = segment.trim();
            if (s.isEmpty()) {
                continue;
            }
            String lower = s.toLowerCase(Locale.ROOT);
            String head = headOf(lower);
            if ("cd".equals(head) || "export".equals(head) || "set".equals(head)) {
                continue;
            }
            boolean source = mentionsSource(lower);
            // Pure Breakpoint (%2)head/tail No source file is available at the back) is not a source read, release.
            if (("head".equals(head) || "tail".equals(head)) && !source) {
                continue;
            }
            if (("cat".equals(head) || "head".equals(head) || "tail".equals(head)
                    || "awk".equals(head)) && source) {
                return "Source is off limits. run/" + head
                        + " Read. Use the inspect mode=read(offset/limit page break)"
                        + " or mode=search; This order was not implemented.";
            }
            if ("sed".equals(head) && source) {
                if (lower.contains(" -i") || lower.contains("--in-place")) {
                    return "Modify Source Ban run/sed -i. Please change to use edit(replace/write)or apply_patch; "
                            + "This order was not implemented.";
                }
                return "Source is off limits. run/sed Read. Use the inspect mode=read/search; "
                        + "This order was not implemented.";
            }
        }
        return null;
    }

    private static String headOf(String segment) {
        int space = segment.indexOf(' ');
        return space < 0 ? segment : segment.substring(0, space);
    }

    private static boolean mentionsSource(String lower) {
        return lower.contains(".java") || lower.contains(".kt")
                || lower.contains(".kts") || lower.contains(".xml")
                || lower.contains(".lua") || lower.contains(".py")
                || lower.contains(".gradle") || lower.contains(".cs");
    }
}
