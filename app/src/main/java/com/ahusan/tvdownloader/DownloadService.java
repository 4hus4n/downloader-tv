package com.ahusan.tvdownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs downloads one at a time in the foreground. */
public class DownloadService extends Service {

    public static final String ACTION_START = "start";
    public static final String ACTION_CANCEL = "cancel";
    public static final String EXTRA_ID = "id";

    private static final String CHANNEL = "downloads";
    private static final int NOTIF_ID = 42;

    static final Set<Long> cancelled = Collections.synchronizedSet(new HashSet<>());

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger pending = new AtomicInteger(0);
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private int lastStartId = 0;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context c, long id) {
        Intent i = new Intent(c, DownloadService.class).setAction(ACTION_START).putExtra(EXTRA_ID, id);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void cancel(Context c, long id) {
        cancelled.add(id);
        DownloadStore store = DownloadStore.get(c);
        DownloadItem d = store.find(id);
        if (d != null && DownloadItem.QUEUED.equals(d.status)) {
            d.status = DownloadItem.CANCELLED;
            store.save();
            store.notifyChanged(true);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvDownloader:dl");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TvDownloader:wifi");
                wifiLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        goForeground("Preparing download…", 0, true);
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            final long id = intent.getLongExtra(EXTRA_ID, -1);
            pending.incrementAndGet();
            executor.execute(() -> {
                try { run(id); } finally {
                    pending.decrementAndGet();
                    main.post(this::stopSelfSafely);
                }
            });
        } else if (pending.get() == 0) {
            stopSelfSafely();
        }
        return START_NOT_STICKY;
    }

    private void stopSelfSafely() {
        if (pending.get() > 0) return;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf(lastStartId);
    }

    private void goForeground(String text, int permille, boolean indeterminate) {
        Notification n = buildNotif(text, permille, indeterminate);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotif(String text, int permille, boolean indeterminate) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("TV Downloader")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(1000, permille, indeterminate)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, piFlags));
        return b.build();
    }

    private void run(long id) {
        DownloadStore store = DownloadStore.get(this);
        DownloadItem d = store.find(id);
        if (d == null) return;
        if (cancelled.remove(id) || DownloadItem.CANCELLED.equals(d.status)) {
            d.status = DownloadItem.CANCELLED;
            store.save();
            store.notifyChanged(true);
            return;
        }

        d.status = DownloadItem.DOWNLOADING;
        d.error = "";
        d.downloaded = 0;
        d.total = -1;
        store.save();
        store.notifyChanged(true);

        HttpURLConnection c = null;
        File part = null;
        try {
            // Follow redirects manually (Java won't follow http <-> https on its own)
            URL url = new URL(d.url);
            for (int hop = 0; ; hop++) {
                c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
                c.setRequestProperty("Accept", "*/*");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || hop >= 10) throw new Exception("Too many redirects");
                    url = new URL(url, loc);
                    continue;
                }
                if (code < 200 || code >= 300) throw new Exception("Server replied HTTP " + code);
                break;
            }

            String mime = c.getContentType();
            d.mime = mime == null ? "" : mime.split(";")[0].trim();
            d.total = -1;
            try {
                String len = c.getHeaderField("Content-Length");
                if (len != null) d.total = Long.parseLong(len.trim());
            } catch (Exception ignored) { }

            String name = nameFromDisposition(c.getHeaderField("Content-Disposition"));
            if (name == null) name = nameFromUrl(url.toString());
            if (name == null) name = nameFromUrl(d.url);
            if (name == null) name = "download";
            name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
            if (name.isEmpty()) name = "download";
            if ("application/vnd.android.package-archive".equalsIgnoreCase(d.mime)
                    && !name.toLowerCase(Locale.US).endsWith(".apk")) {
                name += ".apk";
            }

            File out = uniqueFile(store.getDir(), name);
            d.fileName = out.getName();
            d.path = out.getAbsolutePath();
            part = new File(out.getAbsolutePath() + ".part");
            store.save();
            store.notifyChanged(true);

            long lastTick = System.currentTimeMillis(), lastBytes = 0, lastNotif = 0;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled.contains(id)) throw new CancelledException();
                    os.write(buf, 0, n);
                    d.downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastTick >= 1000) {
                        d.speed = (d.downloaded - lastBytes) * 1000 / (now - lastTick);
                        lastBytes = d.downloaded;
                        lastTick = now;
                    }
                    store.notifyChanged(false);
                    if (now - lastNotif > 1000) {
                        lastNotif = now;
                        String pct = d.total > 0 ? (d.permille() / 10) + "%" : Format.size(d.downloaded);
                        nm.notify(NOTIF_ID, buildNotif(d.fileName + " — " + pct, d.permille(), d.total <= 0));
                    }
                }
            }

            if (d.total > 0 && d.downloaded < d.total) throw new Exception("Connection closed early");
            if (out.exists()) out.delete();
            if (!part.renameTo(out)) throw new Exception("Could not save file");
            d.total = d.downloaded;
            d.status = DownloadItem.DONE;
            d.finishedAt = System.currentTimeMillis();
            d.speed = 0;
            store.save();
            store.notifyChanged(true);
            store.notifyFinished(d);
        } catch (CancelledException e) {
            d.status = DownloadItem.CANCELLED;
            d.speed = 0;
            if (part != null) part.delete();
            store.save();
            store.notifyChanged(true);
        } catch (Exception e) {
            d.status = DownloadItem.FAILED;
            d.speed = 0;
            String msg = e.getMessage();
            d.error = msg == null ? e.getClass().getSimpleName() : msg;
            if (part != null) part.delete();
            store.save();
            store.notifyChanged(true);
        } finally {
            cancelled.remove(id);
            if (c != null) try { c.disconnect(); } catch (Exception ignored) { }
        }
    }

    static class CancelledException extends Exception { }

    static String nameFromDisposition(String cd) {
        if (cd == null) return null;
        try {
            for (String part : cd.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.US).startsWith("filename*=")) {
                    String v = p.substring(10).replace("\"", "");
                    int q = v.indexOf("''");
                    if (q >= 0) v = v.substring(q + 2);
                    return URLDecoder.decode(v, "UTF-8");
                }
            }
            for (String part : cd.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.US).startsWith("filename=")) {
                    String v = p.substring(9).replace("\"", "").trim();
                    if (!v.isEmpty()) return v;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    static String nameFromUrl(String u) {
        try {
            String seg = Uri.parse(u).getLastPathSegment();
            if (seg != null && !seg.trim().isEmpty() && seg.contains(".")) return seg;
        } catch (Exception ignored) { }
        return null;
    }

    static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists() && !new File(f.getPath() + ".part").exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists() && !new File(f.getPath() + ".part").exists()) return f;
        }
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
