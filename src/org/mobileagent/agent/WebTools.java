package org.mobileagent.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.json.JSONObject;

/** Small, deterministic web reader/search used by the phone agent. */
final class WebTools {
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final int MAX_BYTES = 500_000;
    private static final int MAX_TEXT = 14_000;
    private static final int MAX_LINKS = 15;
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9A-Fa-f]+|[A-Za-z]+);");
    private static final Pattern IMAGE_ITEM = Pattern.compile(
            "class=\"iusc\"[^>]*?\\bm=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static final class Result {
        final String data;
        final String error;
        final boolean jsShell;
        final boolean renderSuggested;

        private Result(String data, String error, boolean jsShell, boolean renderSuggested) {
            this.data = data;
            this.error = error;
            this.jsShell = jsShell;
            this.renderSuggested = renderSuggested;
        }

        static Result ok(String data) { return new Result(data, null, false, false); }
        static Result ok(String data, boolean jsShell) {
            return new Result(data, null, jsShell, false);
        }
        static Result fail(String error) { return new Result(null, error, false, false); }
        static Result fail(String error, boolean renderSuggested) {
            return new Result(null, error, false, renderSuggested);
        }
    }

    static final class ImageCandidate {
        final String title;
        final String pageUrl;
        final String imageUrl;
        final String thumbUrl;

        ImageCandidate(String title, String pageUrl, String imageUrl, String thumbUrl) {
            this.title = title == null ? "" : title;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
            this.imageUrl = imageUrl == null ? "" : imageUrl;
            this.thumbUrl = thumbUrl == null ? "" : thumbUrl;
        }
    }

    static final class ImageSearchResult {
        final List<ImageCandidate> candidates;
        final String error;

        ImageSearchResult(List<ImageCandidate> candidates, String error) {
            this.candidates = candidates == null ? new ArrayList<ImageCandidate>() : candidates;
            this.error = error;
        }
    }

    private static final class Page {
        final URL url;
        final String type;
        final byte[] bytes;

        Page(URL url, String type, byte[] bytes) {
            this.url = url;
            this.type = type;
            this.bytes = bytes;
        }
    }

    private static final class StatusException extends IOException {
        final int code;

        StatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private WebTools() { }

    static Result fetch(String urlText) {
        if (urlText == null || urlText.trim().isEmpty()) {
            return Result.fail("Missing address");
        }
        String input = urlText.trim();
        boolean upgraded = false;
        try {
            URL url = new URL(input);
            if ("http".equalsIgnoreCase(url.getProtocol())) {
                url = toHttps(url);
                upgraded = true;
            }
            Page page = get(url, "text/html,application/xhtml+xml,application/xml;q=0.9,"
                    + "text/plain;q=0.8,*/*;q=0.5");
            String type = page.type == null ? "" : page.type.toLowerCase(Locale.ROOT);
            if (!isTextType(type)) {
                return Result.fail("Type of content not supported:" + page.type
                        + ". Current tool reads only pages/Text;PDF, Pictures, etc., should be downloaded to the working area.");
            }
            String body = decode(page.bytes, page.type);
            StringBuilder out = new StringBuilder();
            out.append("Source:").append(page.url).append('\n');
            if (upgraded) {
                out.append("Note: The original link is http, Autoreset https Grab it.\n");
            }
            String text;
            if (type.contains("json")) {
                text = body;
            } else if (type.contains("xml") && !type.contains("html")) {
                text = body;
            } else {
                String title = extractTitle(body);
                if (!title.isEmpty()) {
                    out.append("Title:").append(title).append('\n');
                }
                text = stripHtml(body);
                List<String> links = extractLinks(body, page.url);
                int bodyCap = Math.max(4000, MAX_TEXT - 2500);
                if (text.length() > bodyCap) {
                    text = text.substring(0, bodyCap) + "\n...[Text has been cut.]";
                }
                out.append('\n').append(text);
                if (text.length() < 200) {
                    out.append("\n\n[Hint: The text is very small and the page may need to be login or by JavaScript Rendering;"
                            + "But try web_search Find out open sources or provide publicly accessible links]");
                }
                if (!links.isEmpty()) {
                    out.append("\n\nPage links:\n");
                    for (String link : links) {
                        out.append("- ").append(link).append('\n');
                    }
                }
                boolean jsShell = text.length() < 200
                        && body.toLowerCase(Locale.ROOT).contains("<script");
                String result = out.toString().trim();
                return Result.ok(trimResult(result), jsShell);
            }
            out.append('\n').append(text);
            return Result.ok(trimResult(out.toString().trim()));
        } catch (StatusException e) {
            String hint = e.code == 403 || e.code == 429
                    ? "(Site may require login, need browser execution JavaScript, or restricted to capture)" : "";
            return Result.fail("Capture failed (%1)HTTP " + e.code + ")" + hint,
                    e.code == 403 || e.code == 429);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage() == null ? "Cannot initialise Evolution's mail component." : e.getMessage());
        } catch (java.net.UnknownHostException e) {
            return Result.fail("Domainname cannot be parsed:" + e.getMessage());
        } catch (java.net.SocketTimeoutException e) {
            return Result.fail("Fetch timeout; check the network or change an open link");
        } catch (javax.net.ssl.SSLException e) {
            return Result.fail("HTTPS Could not close temporary folder: %s" + e.getMessage()
                    + "; Internal sites are open or open links are provided using browsers");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Result.fail("Could not close temporary folder: %s" + message);
        }
    }

    static Result search(String queryText) {
        if (queryText == null || queryText.trim().isEmpty()) {
            return Result.fail("Missing search words");
        }
        try {
            String query = URLEncoder.encode(queryText.trim(), "UTF-8");
            URL url = new URL("https://www.bing.com/search?format=rss&q=" + query
                    + "&setlang=zh-CN&mkt=zh-CN");
            Page page = get(url, "application/rss+xml,text/xml,application/xml;q=0.9,*/*;q=0.5");
            String body = new String(page.bytes, StandardCharsets.UTF_8);
            List<String> items = parseRss(body);
            if (items.isEmpty()) {
                return Result.fail("Search does not return results; possibly the search word is too narrow or the search source is restricted by the network");
            }
            StringBuilder out = new StringBuilder("Other Organiser").append(queryText.trim())
                    .append("\nSource:Bing RSS\n");
            for (int i = 0; i < items.size(); i++) {
                out.append('\n').append(i + 1).append(". ").append(items.get(i)).append('\n');
            }
            return Result.ok(trimResult(out.toString().trim()));
        } catch (StatusException e) {
            return Result.fail("Search Failed (%1)HTTP " + e.code + "); But try again or change a keyword later.");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Result.fail("Could not close temporary folder: %s" + message);
        }
    }

    /** Search for open photo candidates; returning only https Pictures, not downloaded here. */
    static ImageSearchResult searchImages(String queryText) {
        if (queryText == null || queryText.trim().isEmpty()) {
            return new ImageSearchResult(null, "Missing search words");
        }
        try {
            String query = URLEncoder.encode(queryText.trim(), "UTF-8");
            URL url = new URL("https://www.bing.com/images/search?q=" + query
                    + "&form=HDRSC2&first=1&tsc=ImageBasicHover");
            Page page = get(url, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5");
            String html = new String(page.bytes, StandardCharsets.UTF_8);
            List<ImageCandidate> candidates = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            Matcher matcher = IMAGE_ITEM.matcher(html);
            while (matcher.find() && candidates.size() < 8) {
                String raw = matcher.group(1)
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&#39;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">");
                try {
                    JSONObject object = new JSONObject(raw);
                    String image = object.optString("murl", "").trim();
                    String thumb = object.optString("turl", "").trim();
                    String pageUrl = object.optString("purl", "").trim();
                    String title = object.optString("t", "").trim();
                    if (image.isEmpty() && thumb.isEmpty()) {
                        continue;
                    }
                    if (!image.isEmpty() && !isAllowed(image)) {
                        image = "";
                    }
                    if (!thumb.isEmpty() && !isAllowed(thumb)) {
                        thumb = "";
                    }
                    if (image.isEmpty() && thumb.isEmpty()) {
                        continue;
                    }
                    String key = image.isEmpty() ? thumb : image;
                    if (!seen.add(key)) {
                        continue;
                    }
                    candidates.add(new ImageCandidate(title, pageUrl, image, thumb));
                } catch (Exception ignored) {
                }
            }
            if (candidates.isEmpty()) {
                return new ImageSearchResult(null,
                        "No candidate was analyzed for photo search; possibly page structure changes or network limitations");
            }
            return new ImageSearchResult(candidates, null);
        } catch (StatusException e) {
            return new ImageSearchResult(null, "Failed to search for pictures (%1)HTTP " + e.code + ")");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ImageSearchResult(null, "Can not get folder: %s: %s" + message);
        }
    }

    private static Page get(URL start, String accept) throws Exception {
        URL url = start;
        for (int redirect = 0; redirect < 8; redirect++) {
            if (!isAllowedWebUrl(url)) {
                throw new IllegalArgumentException("Only the public web is allowed. https Address");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", UA);
                connection.setRequestProperty("Accept", accept);
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("Redirecting of pages is missing Location");
                    }
                    URL next = new URL(url, location);
                    if ("http".equalsIgnoreCase(next.getProtocol())) {
                        next = toHttps(next);
                    }
                    url = next;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new StatusException(code);
                }
                byte[] bytes = readLimited(connection.getInputStream(), MAX_BYTES);
                String encoding = connection.getContentEncoding();
                if (encoding != null
                        && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                    bytes = gunzip(bytes);
                }
                return new Page(url, connection.getContentType(), bytes);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirections");
    }

    static String normalizeUrl(String text) throws Exception {
        URL url = new URL(text.trim());
        if ("http".equalsIgnoreCase(url.getProtocol())) {
            url = toHttps(url);
        }
        return url.toString();
    }

    static boolean isAllowed(String text) {
        try {
            URL url = new URL(text.trim());
            if ("http".equalsIgnoreCase(url.getProtocol())) url = toHttps(url);
            return isAllowedWebUrl(url);
        } catch (Exception e) {
            return false;
        }
    }

    private static URL toHttps(URL url) throws Exception {
        int port = url.getPort();
        if (port == 80 || port == -1) {
            port = -1;
        }
        return new URL("https", url.getHost(), port, url.getFile());
    }

    private static boolean isAllowedWebUrl(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            return false;
        }
        if (url.getUserInfo() != null) {
            return false;
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.equals("localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.contains(":")) {
            return false;
        }
        if (host.startsWith("127.") || host.startsWith("10.")
                || host.startsWith("192.168.") || host.startsWith("169.254.")
                || host.equals("0.0.0.0")) {
            return false;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) {
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return true;
    }

    private static boolean isTextType(String type) {
        if (type == null || type.isEmpty()) {
            return true;
        }
        return type.contains("text/") || type.contains("html") || type.contains("xml")
                || type.contains("json") || type.contains("javascript");
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxBytes) {
                output.write(buffer, 0, Math.max(0, maxBytes - total));
                break;
            }
            output.write(buffer, 0, read);
            total += read;
        }
        input.close();
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return bytes;
        }
        GZIPInputStream input = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        input.close();
        return output.toByteArray();
    }

    private static String decode(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String charset = charsetFromContentType(contentType);
        if (charset == null) {
            charset = charsetFromMeta(bytes);
        }
        if (charset != null) {
            try {
                String decoded = new String(bytes, Charset.forName(charset));
                if (decoded.indexOf('\uFFFD') < 0) {
                    return decoded;
                }
            } catch (Exception ignored) {
            }
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                String gb = new String(bytes, Charset.forName("GB18030"));
                if (gb.indexOf('\uFFFD') < 0) {
                    return gb;
                }
            } catch (Exception ignored) {
            }
        }
        return utf8;
    }

    private static String charsetFromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)")
                .matcher(contentType);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String charsetFromMeta(byte[] bytes) {
        int count = Math.min(bytes.length, 4096);
        String head = new String(bytes, 0, count, StandardCharsets.ISO_8859_1);
        Matcher matcher = Pattern.compile(
                "(?is)<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)")
                .matcher(head);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = Pattern.compile(
                "(?is)<meta[^>]+content\\s*=\\s*[\"'][^\"']*charset\\s*=\\s*([A-Za-z0-9_\\-]+)")
                .matcher(head);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractTitle(String html) {
        Matcher matcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return stripTags(decodeEntities(matcher.group(1))).replaceAll("\\s+", " ").trim();
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        String text = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<noscript.*?</noscript>", " ")
                .replaceAll("(?is)<svg.*?</svg>", " ")
                .replaceAll("(?is)<iframe.*?</iframe>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|h[1-6]|section|article)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        text = decodeEntities(text);
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static String stripTags(String html) {
        return html == null ? "" : html.replaceAll("(?s)<[^>]+>", " ");
    }

    private static List<String> extractLinks(String html, URL base) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = ANCHOR.matcher(html);
        while (matcher.find() && seen.size() < MAX_LINKS) {
            String href = decodeEntities(matcher.group(1)).trim();
            if (href.isEmpty() || href.startsWith("#")
                    || href.toLowerCase(Locale.ROOT).startsWith("javascript:")
                    || href.toLowerCase(Locale.ROOT).startsWith("mailto:")
                    || href.toLowerCase(Locale.ROOT).startsWith("tel:")) {
                continue;
            }
            try {
                URL link = new URL(base, href);
                if (!"http".equalsIgnoreCase(link.getProtocol())
                        && !"https".equalsIgnoreCase(link.getProtocol())) {
                    continue;
                }
                String text = stripTags(decodeEntities(matcher.group(2)))
                        .replaceAll("\\s+", " ").trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.length() > 80) {
                    text = text.substring(0, 80) + "...";
                }
                seen.add(text + " - " + link);
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(seen);
    }

    private static List<String> parseRss(String xml) {
        List<String> items = new ArrayList<>();
        Matcher matcher = ITEM.matcher(xml);
        while (matcher.find() && items.size() < 6) {
            String item = matcher.group(1);
            String title = rssField(item, "title");
            String link = rssField(item, "link");
            String description = rssField(item, "description");
            if (title.isEmpty() && link.isEmpty()) {
                continue;
            }
            description = stripTags(decodeEntities(description))
                    .replaceAll("\\s+", " ").trim();
            if (description.length() > 280) {
                description = description.substring(0, 280) + "...";
            }
            StringBuilder line = new StringBuilder();
            line.append(title.isEmpty() ? "(Untitled)" : title);
            if (!link.isEmpty()) {
                line.append('\n').append("   ").append(link);
            }
            if (!description.isEmpty()) {
                line.append('\n').append("   ").append(description);
            }
            items.add(line.toString());
        }
        return items;
    }

    private static String rssField(String item, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + tag + "[^>]*>(.*?)</" + tag + ">")
                .matcher(item);
        return matcher.find() ? stripTags(decodeEntities(matcher.group(1))).trim() : "";
    }

    private static String decodeEntities(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = ENTITY.matcher(text);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = null;
            try {
                if (name.startsWith("#x") || name.startsWith("#X")) {
                    value = codePoint(Integer.parseInt(name.substring(2), 16));
                } else if (name.startsWith("#")) {
                    value = codePoint(Integer.parseInt(name.substring(1)));
                } else {
                    value = namedEntity(name);
                }
            } catch (Exception ignored) {
            }
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String codePoint(int value) {
        if (value <= 0 || value > Character.MAX_CODE_POINT) {
            return null;
        }
        return new String(Character.toChars(value));
    }

    private static String namedEntity(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            case "hellip": return "...";
            case "mdash": return "-";
            case "ndash": return "–";
            case "ldquo": return "'";
            case "rdquo": return "'";
            case "lsquo": return "'";
            case "rsquo": return "'";
            case "middot": return "·";
            case "bull": return "•";
            case "copy": return "⧉";
            case "reg": return "®";
            case "trade": return "™";
            case "times": return "×";
            case "divide": return "÷";
            default: return null;
        }
    }

    private static String trimResult(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT
                ? text
                : text.substring(0, MAX_TEXT) + "\n...[Content has been cut]";
    }
}
