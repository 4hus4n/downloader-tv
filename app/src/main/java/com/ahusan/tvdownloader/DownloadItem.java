package com.ahusan.tvdownloader;

import org.json.JSONException;
import org.json.JSONObject;

/** One entry in the download history. */
public class DownloadItem {
    public static final String QUEUED = "queued";
    public static final String DOWNLOADING = "downloading";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    public long id;
    public String url;
    public String fileName = "";
    public String path = "";
    public String mime = "";
    public long total = -1;
    public long downloaded = 0;
    public String status = QUEUED;
    public String error = "";
    public long createdAt;
    public long finishedAt;
    public boolean autoInstall = true;

    // runtime only (not saved)
    public long speed = 0;

    public boolean isApk() {
        return fileName.toLowerCase().endsWith(".apk")
                || "application/vnd.android.package-archive".equalsIgnoreCase(mime);
    }

    public boolean isActive() {
        return QUEUED.equals(status) || DOWNLOADING.equals(status);
    }

    public int permille() {
        if (total <= 0) return 0;
        return (int) Math.min(1000, downloaded * 1000 / total);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("url", url);
        o.put("fileName", fileName);
        o.put("path", path);
        o.put("mime", mime);
        o.put("total", total);
        o.put("downloaded", downloaded);
        o.put("status", status);
        o.put("error", error);
        o.put("createdAt", createdAt);
        o.put("finishedAt", finishedAt);
        o.put("autoInstall", autoInstall);
        return o;
    }

    public static DownloadItem fromJson(JSONObject o) {
        DownloadItem d = new DownloadItem();
        d.id = o.optLong("id");
        d.url = o.optString("url");
        d.fileName = o.optString("fileName");
        d.path = o.optString("path");
        d.mime = o.optString("mime");
        d.total = o.optLong("total", -1);
        d.downloaded = o.optLong("downloaded");
        d.status = o.optString("status", FAILED);
        d.error = o.optString("error");
        d.createdAt = o.optLong("createdAt");
        d.finishedAt = o.optLong("finishedAt");
        d.autoInstall = o.optBoolean("autoInstall", true);
        return d;
    }
}
