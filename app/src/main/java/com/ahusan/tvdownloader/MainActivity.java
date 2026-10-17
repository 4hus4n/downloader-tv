package com.ahusan.tvdownloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements DownloadStore.Listener {

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private DownloadStore store;
    private EditText urlInput;
    private CheckBox autoInstall;
    private TextView phoneHint, countText, emptyView;
    private ListView list;
    private final Adapter adapter = new Adapter();
    private PhoneServer phoneServer;

    private boolean resumed = false;
    private boolean waitingForPermission = false;
    private final ArrayDeque<Long> installQueue = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = DownloadStore.get(this);

        urlInput = findViewById(R.id.urlInput);
        autoInstall = findViewById(R.id.autoInstall);
        phoneHint = findViewById(R.id.phoneHint);
        countText = findViewById(R.id.countText);
        emptyView = findViewById(R.id.empty);
        list = findViewById(R.id.list);
        Button btnDownload = findViewById(R.id.btnDownload);
        Button btnClear = findViewById(R.id.btnClear);

        autoInstall.setChecked(getPreferences(MODE_PRIVATE).getBoolean("autoInstall", true));
        autoInstall.setOnCheckedChangeListener((b, checked) ->
                getPreferences(MODE_PRIVATE).edit().putBoolean("autoInstall", checked).apply());

        list.setAdapter(adapter);
        list.setItemsCanFocus(false);
        list.setOnItemClickListener((parent, view, position, id) -> showActions(adapter.getItem(position)));

        btnDownload.setOnClickListener(v -> submitInput());
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitInput();
                return true;
            }
            return false;
        });
        btnClear.setOnClickListener(v -> confirmClear());

        phoneServer = new PhoneServer(url -> runOnUiThread(() -> {
            startDownload(url);
            Toast.makeText(this, "Link received from phone", Toast.LENGTH_SHORT).show();
        }));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        refresh();
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (m.find()) startDownload(m.group());
        else Toast.makeText(this, "No link found in shared text", Toast.LENGTH_SHORT).show();
        intent.setAction(Intent.ACTION_MAIN);
    }

    @Override
    protected void onStart() {
        super.onStart();
        store.addListener(this);
        new Thread(() -> {
            phoneServer.start();
            runOnUiThread(this::updatePhoneHint);
        }).start();
        refresh();
    }

    @Override
    protected void onStop() {
        store.removeListener(this);
        phoneServer.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (waitingForPermission) {
            waitingForPermission = false;
            if (!canInstall()) {
                Toast.makeText(this, "Install permission not granted", Toast.LENGTH_LONG).show();
                installQueue.clear();
                return;
            }
        }
        processInstallQueue();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    // ---------- Downloads ----------

    private void submitInput() {
        String raw = urlInput.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter a link first", Toast.LENGTH_SHORT).show();
            urlInput.requestFocus();
            return;
        }
        if (startDownload(raw)) {
            urlInput.setText("");
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
            list.requestFocus();
            list.setSelection(0);
        }
    }

    private boolean startDownload(String raw) {
        String url = raw.trim();
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = "https://" + url;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (u.getHost() == null || u.getHost().isEmpty()
                || !(scheme.equals("http") || scheme.equals("https"))) {
            Toast.makeText(this, "That doesn't look like a valid http/https link", Toast.LENGTH_LONG).show();
            return false;
        }
        DownloadItem d = store.add(url, autoInstall.isChecked());
        DownloadService.start(this, d.id);
        return true;
    }

    private void retry(DownloadItem d) {
        if (d.path != null && !d.path.isEmpty()) new File(d.path + ".part").delete();
        d.status = DownloadItem.QUEUED;
        d.error = "";
        d.downloaded = 0;
        store.save();
        store.notifyChanged(true);
        DownloadService.start(this, d.id);
    }

    // ---------- Store listener ----------

    @Override
    public void onChanged() { refresh(); }

    @Override
    public void onFinished(DownloadItem d) {
        refresh();
        if (d.isApk() && d.autoInstall) {
            installQueue.add(d.id);
            if (resumed) processInstallQueue();
        } else {
            Toast.makeText(this, "Downloaded: " + d.fileName, Toast.LENGTH_SHORT).show();
        }
    }

    private void refresh() {
        List<DownloadItem> items = store.snapshot();
        adapter.setItems(items);
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        long used = 0;
        for (DownloadItem d : items) {
            if (DownloadItem.DONE.equals(d.status) && d.path != null && !d.path.isEmpty()) {
                used += new File(d.path).length();
            }
        }
        countText.setText(items.isEmpty() ? "" :
                items.size() + (items.size() == 1 ? " item" : " items") + " · " + Format.size(used) + " on device");
    }

    private void updatePhoneHint() {
        String ip = PhoneServer.localIp();
        int port = phoneServer.getPort();
        if (ip != null && port > 0) {
            phoneHint.setText("📱 Send a link from your phone:\nhttp://" + ip + ":" + port);
        } else {
            phoneHint.setText("");
        }
    }

    // ---------- Install ----------

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void processInstallQueue() {
        while (!installQueue.isEmpty()) {
            DownloadItem d = store.find(installQueue.peek());
            if (d == null || !new File(d.path).exists()) { installQueue.poll(); continue; }
            if (!canInstall()) { askInstallPermission(); return; }
            installQueue.poll();
            launchInstaller(d);
            return; // next one installs when we come back to this screen
        }
    }

    private void install(DownloadItem d) {
        installQueue.remove(d.id);
        installQueue.addFirst(d.id);
        processInstallQueue();
    }

    private void askInstallPermission() {
        new AlertDialog.Builder(this)
                .setTitle("Allow app installs")
                .setMessage("To install APKs, turn ON \"Allow from this source\" for TV Downloader on the next screen, then press Back.")
                .setPositiveButton("Open settings", (x, y) -> openUnknownSourcesSettings())
                .setNegativeButton("Cancel", (x, y) -> installQueue.clear())
                .setOnCancelListener(x -> installQueue.clear())
                .show();
    }

    private void openUnknownSourcesSettings() {
        waitingForPermission = true;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
        } catch (ActivityNotFoundException ignored) { }
        try {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            Toast.makeText(this, "Enable \"Unknown sources\" for TV Downloader", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            waitingForPermission = false;
            Toast.makeText(this, "Open Settings › Apps › Special access › Install unknown apps", Toast.LENGTH_LONG).show();
        }
    }

    private void launchInstaller(DownloadItem d) {
        File f = new File(d.path);
        Uri uri = Build.VERSION.SDK_INT >= 24 ? ApkProvider.uriFor(this, f) : Uri.fromFile(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, APK_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                Intent alt = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                alt.setData(uri);
                alt.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(alt);
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "No package installer found on this TV", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openFile(DownloadItem d) {
        File f = new File(d.path);
        Uri uri = Build.VERSION.SDK_INT >= 24 ? ApkProvider.uriFor(this, f) : Uri.fromFile(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, ApkProvider.mimeFor(d.fileName));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app on this TV can open this file", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Dialogs ----------

    private void showActions(DownloadItem d) {
        if (d == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean fileExists = d.path != null && !d.path.isEmpty() && new File(d.path).exists();

        if (d.isActive()) {
            labels.add("✖  Cancel download");
            actions.add(() -> DownloadService.cancel(this, d.id));
        } else if (DownloadItem.DONE.equals(d.status) && fileExists) {
            if (d.isApk()) {
                labels.add("📦  Install");
                actions.add(() -> install(d));
            } else {
                labels.add("▶  Open");
                actions.add(() -> openFile(d));
            }
            labels.add("🗑  Delete file & remove from history");
            actions.add(() -> confirmDelete(d));
            labels.add("Remove from history (keep file)");
            actions.add(() -> store.remove(d, false));
            labels.add("⟳  Download again");
            actions.add(() -> startDownload(d.url));
        } else {
            labels.add("⟳  Retry");
            actions.add(() -> retry(d));
            labels.add("🗑  Remove from history");
            actions.add(() -> store.remove(d, true));
        }

        new AlertDialog.Builder(this)
                .setTitle(d.fileName)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmDelete(DownloadItem d) {
        new AlertDialog.Builder(this)
                .setTitle("Delete file?")
                .setMessage(d.fileName + "\n\nThe file will be deleted from the TV.")
                .setPositiveButton("Delete", (x, y) -> {
                    store.remove(d, true);
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClear() {
        if (store.snapshot().isEmpty()) {
            Toast.makeText(this, "History is already empty", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = {"🗑  Clear history & delete all files", "Clear history only (keep files)"};
        new AlertDialog.Builder(this)
                .setTitle("Clear all")
                .setItems(options, (dialog, which) -> {
                    store.clear(which == 0);
                    Toast.makeText(this, which == 0 ? "History and files deleted" : "History cleared",
                            Toast.LENGTH_SHORT).show();
                    urlInput.requestFocus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- List adapter ----------

    private class Adapter extends BaseAdapter {
        private List<DownloadItem> items = new ArrayList<>();

        void setItems(List<DownloadItem> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public DownloadItem getItem(int p) { return p >= 0 && p < items.size() ? items.get(p) : null; }
        @Override public long getItemId(int p) { return items.get(p).id; }
        @Override public boolean hasStableIds() { return true; }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_download, parent, false);
            DownloadItem d = items.get(position);
            TextView badge = v.findViewById(R.id.badge);
            TextView name = v.findViewById(R.id.name);
            TextView info = v.findViewById(R.id.info);
            TextView status = v.findViewById(R.id.status);
            ProgressBar progress = v.findViewById(R.id.progress);

            boolean apk = d.isApk();
            badge.setText(apk ? "APK" : extOf(d.fileName));
            badge.setBackgroundResource(apk ? R.drawable.badge_apk : R.drawable.badge_file);
            name.setText(d.fileName);

            int dim = getResources().getColor(R.color.text_dim);
            switch (d.status) {
                case DownloadItem.DOWNLOADING: {
                    progress.setVisibility(View.VISIBLE);
                    progress.setIndeterminate(d.total <= 0);
                    progress.setProgress(d.permille());
                    String sizes = Format.size(d.downloaded) + (d.total > 0 ? " / " + Format.size(d.total) : "");
                    info.setText(sizes + (d.speed > 0 ? " · " + Format.size(d.speed) + "/s" : ""));
                    status.setText(d.total > 0 ? (d.permille() / 10) + "%" : "…");
                    status.setTextColor(getResources().getColor(R.color.accent));
                    break;
                }
                case DownloadItem.QUEUED:
                    progress.setVisibility(View.VISIBLE);
                    progress.setIndeterminate(true);
                    info.setText("Waiting… · " + d.url);
                    status.setText("Queued");
                    status.setTextColor(dim);
                    break;
                case DownloadItem.DONE: {
                    progress.setVisibility(View.GONE);
                    boolean exists = new File(d.path).exists();
                    info.setText(Format.size(d.total) + " · " + Format.date(d.finishedAt)
                            + (exists ? (apk ? " · OK to install" : " · OK to open") : " · file deleted"));
                    status.setText(exists ? "✓ Done" : "Missing");
                    status.setTextColor(exists ? getResources().getColor(R.color.accent) : dim);
                    break;
                }
                case DownloadItem.CANCELLED:
                    progress.setVisibility(View.GONE);
                    info.setText("Cancelled · " + Format.date(d.createdAt) + " · OK to retry");
                    status.setText("Cancelled");
                    status.setTextColor(dim);
                    break;
                default:
                    progress.setVisibility(View.GONE);
                    info.setText((d.error == null || d.error.isEmpty() ? "Failed" : d.error) + " · OK to retry");
                    status.setText("Failed");
                    status.setTextColor(getResources().getColor(R.color.danger));
            }
            return v;
        }

        private String extOf(String n) {
            int dot = n == null ? -1 : n.lastIndexOf('.');
            if (dot < 0 || dot == n.length() - 1) return "FILE";
            String e = n.substring(dot + 1).toUpperCase();
            return e.length() > 4 ? e.substring(0, 4) : e;
        }
    }
}
