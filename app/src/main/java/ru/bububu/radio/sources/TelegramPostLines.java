package ru.bububu.radio.sources;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ru.bububu.radio.core.Article;

/** Read standalone lines of a verified Telegram body. Every offset still addresses the stored source. */
final class TelegramPostLines {
    private TelegramPostLines() { }

    private static boolean edgeControl(char c) { return c == '\u200b' || c == '\ufeff'; }

    static List<ContextualSpeech.Reading> readings(Article article, int start, int end) {
        List<ContextualSpeech.Reading> out = new ArrayList<>();
        String full = article.text;
        int body = article.sourceVersion.bodyStart, bodyEnd = article.sourceVersion.bodyEnd;
        if (end <= body) return out;
        int firstLine = Math.max(body, full.lastIndexOf('\n', start-1)+1);
        // In the common case there is no fence. Only rewind to the body start
        // when an earlier visible fenced code block could contain this sentence.
        boolean fences = full.indexOf("```") >= 0 || full.indexOf("~~~") >= 0;
        char fence = 0;
        int width = 0;
        for (int line = fences ? body : firstLine; line < end;) {
            int nl = full.indexOf('\n', line);
            int stop = nl < 0 ? bodyEnd : Math.min(nl, bodyEnd);
            int lo = line, hi = stop;
            while (lo < hi && Character.isWhitespace(full.charAt(lo))) lo++;
            while (hi > lo && Character.isWhitespace(full.charAt(hi-1))) hi--;
            if (fences && lo < hi) {
                char mark = full.charAt(lo);
                int n = lo;
                if (mark == '`' || mark == '~') while (n < hi && full.charAt(n) == mark) n++;
                if (n-lo >= 3 && fence == 0) { fence = mark; width = n-lo; }
                else if (mark == fence && n-lo >= width && n == hi) { fence = 0; width = 0; }
                else if (fence == 0 && stop > start) addLine(full, lo, hi, start, end, out);
            } else if (fence == 0 && stop > start) addLine(full, lo, hi, start, end, out);
            if (stop >= bodyEnd) break;
            line = stop+1;
        }
        return out;
    }

    private static void addLine(String full, int lo, int hi, int start, int end, List<ContextualSpeech.Reading> out) {
        int first = lo, last = hi;
        while (first < last && edge(full.charAt(first))) first++;
        while (last > first && edge(full.charAt(last-1))) last--;
        addEdgeControls(full, lo, first, start, end, out);
        addEdgeControls(full, last, hi, start, end, out);
        int length = last-first;
        if (length < 8 || length > SourceLimits.MAX_URL || !full.regionMatches(true, first, "http", 0, 4)) return;
        if (!publicLink(full.substring(first, last))) return;
        // Currently generated sentence offsets never bisect a URI token. Older
        // reviewed offsets can. Validate the whole original line before speaking
        // either part; neither a partial hostname nor path is proof of a link.
        String phrase = first >= start && last <= end ? "В тексте есть ссылка"
                : first >= start ? "В тексте" : last <= end ? "есть ссылка" : "продолжение ссылки";
        add(first, last, start, end, phrase, "telegram-standalone-link", out);
    }

    private static boolean edge(char c) { return edgeControl(c) || Character.isWhitespace(c); }

    private static void addEdgeControls(String text, int lo, int hi, int start, int end, List<ContextualSpeech.Reading> out) {
        for (int at = lo; at < hi;) {
            if (!edgeControl(text.charAt(at))) { at++; continue; }
            int run = at++;
            while (at < hi && edgeControl(text.charAt(at))) at++;
            add(run, at, start, end, " ", "telegram-boundary-control", out);
        }
    }

    private static void add(int lo, int hi, int start, int end, String spoken, String kind, List<ContextualSpeech.Reading> out) {
        int from = Math.max(lo, start)-start, to = Math.min(hi, end)-start;
        if (from < to) out.add(new ContextualSpeech.Reading(from, to, from, to, spoken, kind));
    }

    /** Strict HTTP(S) URI syntax; a web link need not be fetched or resolved to be announced. */
    private static boolean publicLink(String value) {
        try {
            URI uri = new URI(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return false;
            String authority = uri.getRawAuthority();
            if (authority == null || authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0
                    || authority.endsWith(":") || value.codePoints().anyMatch(c -> Character.getType(c) == Character.FORMAT)) return false;
            // Encode only the host. URI.parseServerAuthority then validates the actual
            // port and IPv6 grammar without resolving ordinary DNS names.
            if (!authority.startsWith("[")) {
                int colon = authority.indexOf(':');
                String name = colon < 0 ? authority : authority.substring(0, colon);
                authority = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES) + (colon < 0 ? "" : authority.substring(colon));
            }
            URI server = new URI(uri.getScheme()+"://"+authority).parseServerAuthority();
            if (server.getPort() == 0 || server.getPort() > 65535) return false;
            String host = server.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("[")) {
                // URI has already verified an IPv6 literal; no hostname reaches getByName.
                return new PublicIpPolicy().isPublic(InetAddress.getByName(host));
            }
            if (host.endsWith(".")) host = host.substring(0, host.length()-1);
            if (host.matches("[0-9.]+")) {
                String[] octets = host.split("\\.", -1);
                if (octets.length != 4) return false;
                byte[] bytes = new byte[4];
                for (int i=0; i<4; i++) {
                    if (!octets[i].matches("0|[1-9][0-9]{0,2}")) return false;
                    int octet = Integer.parseInt(octets[i]);
                    if (octet > 255) return false;
                    bytes[i] = (byte)octet;
                }
                return new PublicIpPolicy().isPublic(InetAddress.getByAddress(bytes));
            }
            int dot = host.lastIndexOf('.');
            return dot > 0 && host.length() <= 253 && !host.equals("home.arpa") && !host.endsWith(".home.arpa")
                    && !host.substring(dot+1).matches("localhost|local|localdomain|internal|lan|home|corp|private|test|invalid|example|onion");
        } catch (URISyntaxException | IllegalArgumentException | UnknownHostException invalid) { return false; }
    }
}
