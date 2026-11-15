package ru.bububu.silero.stressprobe;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.LocalTextReject;
import ru.bububu.radio.core.PlayerController;
import ru.bububu.radio.core.TextSegments;
import ru.bububu.radio.models.SileroSpeechInput;
import ru.bububu.radio.sources.SourceLimits;
import ru.bububu.radio.sources.SourcePronunciation48;
import ru.bububu.radio.sources.SourcePublication45;
import ru.bububu.radio.sources.SourceSpeechPlan45;
import ru.bububu.radio.sources.SourceVersionRecord;

/** Private output includes publisher text. Tests the Android app's real Java planner, not native audio. */
public final class CorpusSpeechAudit {
    private static final String PACK_SHA = "c97eeb96cd2b4e2806f056a515e2f2f48600041e59e9e70e812d274757c1587f";
    private static final String RESOURCE_SHA = "8b280e0a8ea7798aa06fe9b4dbff0bfaede7316a9fa274cf6ca67c37a68ecef3";
    // RadioService constructs PlayerController with 1000; the source45 adapter additionally caps at 512.
    private static final int MAX_CHARS = 1000;

    private static String optional(JSONObject o, String k) throws Exception {
        return o.isNull(k) ? null : o.getString(k);
    }

    private static SourceVersionRecord version(JSONObject row) throws Exception {
        String encoded = optional(row, "publicationBase64"), context = optional(row, "contextClass");
        List<Integer> starts = new ArrayList<>();
        JSONArray offsets = row.getJSONArray("sentenceStarts");
        for (int n = 0; n < offsets.length(); n++) starts.add(offsets.getInt(n));
        SourceVersionRecord result = new SourceVersionRecord(
                row.getString("sourceId"), row.getString("itemKey"), row.getString("articleId"), row.getString("versionKey"),
                row.getString("displayTitle"), row.getString("providerTitle"), row.getString("sourceLabel"), optional(row, "url"),
                row.getString("completeness"), row.getString("reason"), row.getString("method"), row.getString("adapterVersion"),
                row.getString("normalizerVersion"), row.getString("framingVersion"), row.getString("bodyHash"), row.getString("spokenText"),
                row.getInt("bodyStart"), row.getInt("bodyEnd"), row.getLong("receivedAt"), optional(row, "cacheLocator"),
                row.getInt("spokenLength"), starts,
                encoded == null ? null : SourcePublication45.decode(Base64.getDecoder().decode(encoded)),
                context == null ? null : SourcePublication45.Context.valueOf(context));
        if (!result.body().equals(row.getString("body"))) throw new IllegalStateException("Article body binding failed");
        return result;
    }

    private static JSONArray diagnose(FrontendPlan.Data dictionary, PlayerController.SpeechSentence sentence) throws Exception {
        JSONArray result = new JSONArray();
        String text = sentence.text();
        // Localize obvious tokens with the same frontend. A token-level observation is diagnostic only.
        var tokens = java.util.regex.Pattern.compile("\\S+").matcher(text);
        while (tokens.find()) {
            try {
                String projection;
                if (SourceSpeechPlan45.source45(sentence.article)) {
                    SourceSpeechPlan45.Plan atom = SourceSpeechPlan45.compile(tokens.group());
                    projection = atom.project(0, tokens.group().length());
                } else {
                    projection = SileroSpeechInput.forSentence(sentence).project(tokens.start(), tokens.end());
                }
                new FrontendPlan(dictionary, projection);
            } catch (IllegalArgumentException unsupported) {
                JSONObject fact = new JSONObject();
                fact.put("start", tokens.start()).put("end", tokens.end()).put("raw", tokens.group())
                        .put("code", unsupported.getMessage());
                result.put(fact);
            }
        }
        return result;
    }

    private static JSONObject sentence(FrontendPlan.Data dictionary, SourceVersionRecord version, Article article, int index) throws Exception {
        PlayerController.SpeechSentence sentence = new PlayerController.SpeechSentence("SRC:" + version.sourceId, article, null, index);
        Article.Sentence bounds = article.sentences.get(index);
        JSONObject row = new JSONObject().put("index", index).put("start", bounds.start).put("end", bounds.end)
                .put("raw", bounds.text);
        JSONArray fragments = new JSONArray();
        try {
            List<String> raw = SourceSpeechPlan45.source45(article)
                    ? RadioFragments.source45(dictionary, SourceSpeechPlan45.sentence(sentence), Math.min(512, MAX_CHARS))
                    : RadioFragments.plan(dictionary, SileroSpeechInput.forSentence(sentence), MAX_CHARS);
            TextSegments.checkedFragments(bounds.text, raw, MAX_CHARS);
            int cursor = 0;
            for (int n = 0; n < raw.size(); n++) {
                PlayerController.SpeechUnit unit = new PlayerController.SpeechUnit(sentence, raw, n);
                while (cursor < bounds.text.length() && Character.isWhitespace(bounds.text.charAt(cursor))) cursor++;
                if (!bounds.text.startsWith(unit.text, cursor)) throw new IllegalStateException("Fragment binding failed");
                String projected = SourceSpeechPlan45.project(unit);
                // RepeatableSilero uses the same FrontendPlan on this projected synthesis input.
                FrontendPlan frontend = new FrontendPlan(dictionary, projected);
                fragments.put(new JSONObject().put("index", n).put("start", bounds.start + cursor)
                        .put("end", bounds.start + cursor + unit.text.length()).put("raw", unit.text)
                        .put("inputHash", SourceSpeechPlan45.hash(unit)).put("projected", projected)
                        .put("frontendSpoken", frontend.spoken).put("needsHomo", frontend.needsHomo()));
                cursor += unit.text.length();
            }
            row.put("status", "ACCEPTED");
        } catch (IllegalArgumentException rejected) {
            row.put("reason", rejected.getMessage());
            row.put("status", LocalTextReject.preflight(rejected) != null ? "RECOVERABLE_TEXT_REJECTION" : "OTHER_REJECTION");
            row.put("tokenDiagnostics", diagnose(dictionary, sentence));
        }
        return row.put("fragments", fragments);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException("PACK OUTPUT SPLIT CORPUS ...");
        Path pack = Path.of(args[0]), output = Path.of(args[1]);
        if (!SourceLimits.hash(Files.readAllBytes(pack)).equals(PACK_SHA)) throw new IllegalStateException("Pack checksum");
        FrontendPlan.Data dictionary;
        try (ZipFile z = new ZipFile(pack.toFile()); InputStream input = z.getInputStream(z.getEntry("resources.json"))) {
            byte[] bytes = input.readAllBytes();
            if (!SourceLimits.hash(bytes).equals(RESOURCE_SHA)) throw new IllegalStateException("Resource checksum");
            dictionary = ResourceData.parse(bytes);
        }
        SourcePronunciation48.dictionary();
        for (int i = 3; i < args.length; i++) {
            byte[] bytes = Files.readAllBytes(Path.of(args[i]));
            JSONObject doc = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!doc.getString("split").equals(args[2])) throw new IllegalArgumentException("Unexpected split");
            String id = doc.getString("id");
            if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid corpus ID");
            SourceVersionRecord version = version(doc);
            Article article = version.asArticle();
            JSONArray sentences = new JSONArray();
            for (int index = 0; index < article.sentences.size(); index++)
                sentences.put(sentence(dictionary, version, article, index));
            JSONObject audit = new JSONObject().put("id", id).put("split", args[2]).put("inputSha256", SourceLimits.hash(bytes))
                    .put("sourceId", version.sourceId).put("articleId", version.articleId).put("versionKey", version.versionKey)
                    .put("source", doc.getString("source")).put("method", version.method).put("framingVersion", version.framingVersion)
                    .put("bodyStart", version.bodyStart).put("bodyEnd", version.bodyEnd).put("sentences", sentences)
                    .put("resourceSha256", RESOURCE_SHA).put("pronunciationStamp", SourcePronunciation48.stamp());
            Files.writeString(output.resolve(id + ".json"), audit.toString(2) + "\n", StandardOpenOption.CREATE_NEW);
        }
    }
}
