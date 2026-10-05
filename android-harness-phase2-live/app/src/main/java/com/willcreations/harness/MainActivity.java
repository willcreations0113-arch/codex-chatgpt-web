package com.willcreations.harness;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity implements ShizukuBridge.Listener {
    private TextView status;
    private TextView shizukuStatus;
    private TextView output;
    private TaskStore store;
    private ShizukuBridge shizuku;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new TaskStore(this);
        shizuku = new ShizukuBridge(this, this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        shizukuStatus = new TextView(this);
        output = new TextView(this);
        output.setTextIsSelectable(true);

        Button access = button("Accessibility設定", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button demo = button("自律ループを開始", () -> {
            store.set("WAIT_TARGET", 0);
            startActivity(new Intent(this, DemoTargetActivity.class));
        });
        Button connect = button("Shizuku接続・権限", shizuku::ensureConnected);
        Button packages = button("アプリ一覧（読取）", shizuku::listPackages);
        Button processes = button("プロセス一覧（読取）", shizuku::listProcesses);
        Button logs = button("Logcat（読取）", shizuku::readLogcat);

        root.addView(status);
        root.addView(shizukuStatus);
        root.addView(access);
        root.addView(demo);
        root.addView(connect);
        root.addView(packages);
        root.addView(processes);
        root.addView(logs);
        root.addView(output, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        shizuku.start();
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText("Task state: " + store.state() + " / attempt=" + store.attempt());
    }

    @Override
    protected void onDestroy() {
        if (shizuku != null) shizuku.stop();
        super.onDestroy();
    }

    @Override
    public void onState(String state) {
        shizukuStatus.setText("Shizuku: " + state);
    }

    @Override
    public void onResult(String title, String result) {
        output.setText("[" + title + "]\n" + result);
    }
}
