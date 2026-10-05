package com.willcreations.harness;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements ShizukuBridge.Listener, AgentRunner.Listener {
    private TextView status;
    private TextView shizukuStatus;
    private TextView apiStatus;
    private TextView agentStatus;
    private TextView output;
    private EditText apiKeyInput;
    private EditText goalInput;
    private TaskStore store;
    private SecretStore secrets;
    private ShizukuBridge shizuku;
    private AgentRunner agent;
    private final StringBuilder agentLog = new StringBuilder();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new TaskStore(this);
        secrets = new SecretStore(this);
        shizuku = new ShizukuBridge(this, this);
        agent = new AgentRunner(this, shizuku, this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        shizukuStatus = new TextView(this);
        apiStatus = new TextView(this);
        agentStatus = new TextView(this);
        agentStatus.setText("AI Agent: idle");

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("OpenAI API key（端末Keystoreで暗号化保存）");
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        goalInput = new EditText(this);
        goalInput.setHint("AIにしてほしいこと");
        goalInput.setText("Bluetooth設定を開いて、Bluetooth画面に到達したことを確認して");
        goalInput.setMinLines(2);
        goalInput.setMaxLines(4);

        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setPadding(0, 24, 0, 48);

        Button access = button("Accessibility設定", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button demo = button("固定自律テスト", () -> {
            store.set("WAIT_TARGET", 0);
            startActivity(new Intent(this, DemoTargetActivity.class));
        });
        Button connect = button("Shizuku接続・権限", shizuku::ensureConnected);
        Button packages = button("アプリ一覧（読取）", shizuku::listPackages);
        Button processes = button("プロセス一覧（読取）", shizuku::listProcesses);
        Button logs = button("Logcat（読取）", shizuku::readLogcat);
        Button saveKey = button("OpenAI APIキーを保存", this::saveApiKey);
        Button startAi = button("AIタスク開始", this::startAgent);
        Button stopAi = button("AIタスク停止", () -> agent.cancel());

        root.addView(status);
        root.addView(shizukuStatus);
        root.addView(access);
        root.addView(demo);
        root.addView(connect);
        root.addView(packages);
        root.addView(processes);
        root.addView(logs);

        TextView phase = new TextView(this);
        phase.setText("\nPhase 4 — AI Planner");
        phase.setTextSize(20);
        root.addView(phase);
        root.addView(apiStatus);
        root.addView(apiKeyInput);
        root.addView(saveKey);
        root.addView(goalInput);
        root.addView(startAi);
        root.addView(stopAi);
        root.addView(agentStatus);
        root.addView(output, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        shizuku.start();
        refreshApiStatus();
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void saveApiKey() {
        String value = apiKeyInput.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "APIキーを入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            secrets.saveOpenAiKey(value);
            apiKeyInput.setText("");
            refreshApiStatus();
            Toast.makeText(this, "APIキーを暗号化保存しました", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            apiStatus.setText("API key: 保存失敗 " + t.getClass().getSimpleName());
        }
    }

    private void startAgent() {
        try {
            String key = secrets.loadOpenAiKey();
            if (key.isEmpty()) {
                agentStatus.setText("AI Agent: APIキーが未設定です");
                return;
            }
            String goal = goalInput.getText().toString().trim();
            agentLog.setLength(0);
            output.setText("");
            agent.start(goal, key);
        } catch (Throwable t) {
            agentStatus.setText("AI Agent: KeyStore error: " + t.getClass().getSimpleName());
        }
    }

    private void refreshApiStatus() {
        apiStatus.setText(secrets.hasOpenAiKey()
                ? "OpenAI API key: configured"
                : "OpenAI API key: not configured");
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText("Task state: " + store.state() + " / attempt=" + store.attempt());
        refreshApiStatus();
    }

    @Override
    protected void onDestroy() {
        if (agent != null) agent.shutdown();
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

    @Override
    public void onAgentState(String state) {
        agentStatus.setText("AI Agent: " + state);
        status.setText("Task state: " + store.state() + " / attempt=" + store.attempt());
    }

    @Override
    public void onAgentLog(String line) {
        if (agentLog.length() > 12000) {
            agentLog.delete(0, agentLog.length() - 9000);
        }
        agentLog.append(line).append('\n');
        output.setText("[Agent]\n" + agentLog);
    }
}
