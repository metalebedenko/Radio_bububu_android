package ru.bububu.radio;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.bububu.radio.core.Article;
import ru.bububu.radio.core.Station;
import ru.bububu.radio.core.PlayerController;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;

final class LocalData implements PlayerController.StateStore {
    private final AtomicFile stateFile;
    private boolean restoreFailed;
    LocalData(Context context) {
        stateFile = new AtomicFile(new File(context.getFilesDir(), "player-state-v1.json"));
    }

    static List<Station> fixtures(Context context) {
        try (InputStream input = context.getAssets().open("fixtures.json")) {
            JSONObject data = new JSONObject(readText(input));
            JSONArray stations = data.getJSONArray("stations");
            List<Station> result = new ArrayList<>();
            for (int s = 0; s < stations.length(); s++) {
                JSONObject station = stations.getJSONObject(s);
                JSONArray articles = station.getJSONArray("articles");
                List<Article> items = new ArrayList<>();
                for (int a = 0; a < articles.length(); a++) {
                    JSONObject article = articles.getJSONObject(a);
                    JSONArray sentences = article.getJSONArray("sentences");
                    String[] text = new String[sentences.length()];
                    for (int i = 0; i < text.length; i++) text[i] = sentences.getString(i);
                    items.add(Article.fromSentences(article.getString("id"), article.getString("version"),
                            article.getString("title"), text));
                }
                result.add(new Station(station.getString("id"), station.getString("name"), items));
            }
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("Встроенные тексты повреждены", failure);
        }
    }

    @Override public PlayerController.SavedState load() {
        try {
            byte[] bytes = readInert();
            restoreFailed = false;
            if (bytes == null) return null;
            JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (data.getInt("schema") != 1) throw new IllegalStateException("Unknown state schema");
            Map<String, String> cursors = new LinkedHashMap<>();
            JSONObject stations = data.getJSONObject("stationArticleIds");
            for (java.util.Iterator<String> it = stations.keys(); it.hasNext();) {
                String key = it.next(); cursors.put(key, stations.getString(key));
            }
            Map<String, PlayerController.SavedProgress> progress = new LinkedHashMap<>();
            JSONArray articles = data.getJSONArray("progress");
            for (int i = 0; i < articles.length(); i++) {
                JSONObject p = articles.getJSONObject(i);
                List<Integer> completed = new ArrayList<>();
                JSONArray coverage = p.getJSONArray("completed");
                for (int c = 0; c < coverage.length(); c++) completed.add(coverage.getInt(c));
                PlayerController.SavedProgress value = new PlayerController.SavedProgress(
                        p.getString("station"), p.getString("article"), p.getString("version"),
                        p.getInt("sentence"), p.getBoolean("passEnded"), completed,
                        p.getBoolean("read"), p.getBoolean("started"));
                progress.put(PlayerController.progressKey(value.stationId, value.articleId, value.version), value);
            }
            return new PlayerController.SavedState(data.getString("currentStationId"), cursors, progress);
        } catch (Exception failure) {
            restoreFailed = true;
            throw new IllegalStateException("Cannot restore state; files preserved", failure);
        }
    }

    boolean restoreFailed() { return restoreFailed; }

    /** Match AtomicFile's committed .bak precedence without openRead recovery mutations. */
    private byte[] readInert() throws IOException {
        File base = stateFile.getBaseFile();
        File parent = base.getParentFile();
        File[] paths = {base, new File(base.getPath()+".bak"), new File(base.getPath()+".new")};
        requireParent(parent);
        boolean[] present = new boolean[3];
        byte[][] contents = new byte[3][];
        for (int i=0;i<3;i++) {
            present[i]=regularOrAbsent(paths[i]);
            if (present[i]) try (FileInputStream input=new FileInputStream(paths[i])) {
                try { if(!OsConstants.S_ISREG(Os.fstat(input.getFD()).st_mode)) throw new IOException("State type changed"); }
                catch(ErrnoException failure) { throw new IOException("Cannot inspect state",failure); }
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int count;
                while((count=input.read(buffer))!=-1) {
                    if (count > ru.bububu.radio.sources.SourceLimits.MAX_RADIO_BYTES-bytes.size()) throw new IOException("State exceeds 16 MiB");
                    bytes.write(buffer,0,count);
                }
                contents[i]=bytes.toByteArray();
            }
        }
        requireParent(parent);
        for(int i=0;i<3;i++) if(regularOrAbsent(paths[i])!=present[i]) throw new IOException("State layout changed");
        if(contents[1]!=null) return contents[1];
        if(contents[0]!=null) return contents[0];
        if(contents[2]!=null) throw new IOException("Only unfinished state is present");
        return null;
    }
    private static void requireParent(File parent) throws IOException {
        try {
            if(parent==null || !OsConstants.S_ISDIR(Os.lstat(parent.getPath()).st_mode) || parent.list()==null)
                throw new IOException("State parent inaccessible");
        } catch(ErrnoException failure) { throw new IOException("Cannot inspect state parent",failure); }
    }
    private static boolean regularOrAbsent(File path) throws IOException {
        try {
            if(!OsConstants.S_ISREG(Os.lstat(path.getPath()).st_mode)) throw new IOException("Unexpected state type");
            return true;
        } catch(ErrnoException failure) {
            if(failure.errno==OsConstants.ENOENT) return false;
            throw new IOException("Cannot inspect state",failure);
        }
    }

    @Override public void save(PlayerController.SavedState state) {
        if (restoreFailed) throw new IllegalStateException("Preserve unreadable radio state");
        FileOutputStream output = null;
        try {
            JSONObject data = new JSONObject();
            data.put("schema", 1);
            data.put("currentStationId", state.currentStationId);
            data.put("stationArticleIds", new JSONObject(state.stationArticleIds));
            JSONArray articles = new JSONArray();
            for (PlayerController.SavedProgress p : state.progress.values()) {
                JSONObject item = new JSONObject();
                item.put("station", p.stationId); item.put("article", p.articleId); item.put("version", p.version);
                item.put("sentence", p.sentenceIndex); item.put("passEnded", p.passEnded);
                item.put("completed", new JSONArray(p.completedSentences));
                item.put("read", p.read); item.put("started", p.started);
                articles.put(item);
            }
            data.put("progress", articles);
            byte[] encoded = data.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > ru.bububu.radio.sources.SourceLimits.MAX_RADIO_BYTES) throw new IOException("State exceeds 16 MiB");
            output = stateFile.startWrite(); output.write(encoded);
            stateFile.finishWrite(output); output = null;
            if (!java.util.Arrays.equals(readInert(), encoded)) throw new IOException("State publication not confirmed");
        } catch (Exception failure) {
            if (output != null) stateFile.failWrite(output);
            throw new IllegalStateException("Cannot save state", failure);
        }
    }

    private static String readText(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
