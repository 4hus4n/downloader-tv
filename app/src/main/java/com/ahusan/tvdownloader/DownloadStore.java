package com.ahusan.tvdownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory list of downloads, persisted to SharedPreferences as JSON. */
public class DownloadStore {

    public interface Listener {
        void onChanged();
        void onFinished(DownloadItem item);
    }

    private static DownloadStore instance;

    private final SharedPreferences prefs;
    private final File dir;
    private final List<DownloadItem> items = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastNotify = 0;

    public static synchronized DownloadStore get(Context c) {
        if (instance == null) instance = new DownloadStore(c.getApplicationContext());
        return instance;
    }

    private DownloadStore(Context c) {
        prefs = c.getSharedPreferences("downloads", Context.MODE_PRIVATE);
        File d = c.getExternalFilesDir("Downloads");
        if (d == null) d = new File(c.getFilesDir(), "Downloads");
        if (!d.exists()) d.mkdirs();
        dir = d;
        load();
    }

    public File getDir() { return dir; }

    private void load() {
        try {
            JSONArray a = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                DownloadItem d = DownloadItem.fromJson(a.getJSONObject(i));
                if (d.isActive()) { // app was killed mid-download
                    d.status = DownloadItem.FAILED;
                    d.error = "Interrupted";
                }
                items.add(d);
            }
        } catch (Exception ignored) { }
    }

    public synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (DownloadItem d : items) a.put(d.toJson());
            prefs.edit().putString("items", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    public synchronized List<DownloadItem> snapshot() {
        return new ArrayList<>(items);
    }

    public synchronized DownloadItem find(long id) {
        for (DownloadItem d : items) if (d.id == id) return d;
        return null;
    }

    public synchronized DownloadItem add(String url, boolean autoInstall) {
        DownloadItem d = new DownloadItem();
        d.id = System.currentTimeMillis();
        while (find(d.id) != null) d.id++;
        d.url = url;
        d.fileName = guessName(url);
        d.createdAt = System.currentTimeMillis();
        d.autoInstall = autoInstall;
        items.add(0, d); // newest first
        save();
        notifyChanged(true);
        return d;
    }

    /** Removes from history; optionally deletes the file too. */
    public synchronized void remove(DownloadItem d, boolean deleteFile) {
        items.remove(d);
        if (deleteFile) deleteFiles(d);
        save();
        notifyChanged(true);
    }

    public synchronized void clear(boolean deleteFiles) {
        List<DownloadItem> keep = new ArrayList<>();
        for (DownloadItem d : items) {
            if (d.isActive()) { keep.add(d); continue; }
            if (deleteFiles) deleteFiles(d);
        }
        items.clear();
        items.addAll(keep);
        save();
        notifyChanged(true);
    }

    private void deleteFiles(DownloadItem d) {
        if (d.path == null || d.path.isEmpty()) return;
        new File(d.path).delete();
        new File(d.path + ".part").delete();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** force=false is throttled (used for progress ticks). */
    public void notifyChanged(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 400) return;
        lastNotify = now;
        main.post(() -> { for (Listener l : listeners) l.onChanged(); });
    }

    public void notifyFinished(DownloadItem d) {
        main.post(() -> { for (Listener l : listeners) l.onFinished(d); });
    }

    static String guessName(String url) {
        try {
            String p = android.net.Uri.parse(url).getLastPathSegment();
            if (p != null && !p.trim().isEmpty()) return p;
        } catch (Exception ignored) { }
        return "download";
    }
}
