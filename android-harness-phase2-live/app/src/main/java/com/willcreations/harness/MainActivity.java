package com.willcreations.harness;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements ShizukuBridge.Listener, AgentRunner.Listener {
    private TextView status;
    private TextView shizukuStatus;
    private TextView providerStatus;
    private TextView agentStatus;
    private TextView output;
    private Spinner providerSpinner;
    private EditText modelInput;
    private EditText baseUrlInput;
    private EditText apiKeyInput;
    private EditText goalInput;
    private TaskStore store;
    private SecretStore secrets;
    private ProviderStore providerStore;
    private ShizukuBridge shizuku;
    private AgentRunner agent;
    private final StringBuilder agentLog = new StringBuilder();

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new TaskStore(this);
        secrets = new SecretStore(this);
        providerStore = new ProviderStore(this);
        shizuku = new ShizukuBridge(this, this);
        agent = new AgentRunner(this, shizuku, this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        shizukuStatus = new TextView(this);
        providerStatus = new TextView(this);
        agentStatus = new TextView(this);
        agentStatus.setText("AI Agent: idle");

        providerSpinner = new Spinner(this);
        String[] labels = new String[ProviderConfig.Type.values().length];
        for (int i = 0; i < labels.length; i++) labels[i] = ProviderConfig.Type.values()[i].label;
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(providerAdapter);

        modelInput = new EditText(this);
        modelInput.setHint("Model ID");
        modelInput.setSingleLine(true);

        baseUrlInput = new EditText(this);
        baseUrlInput.setHint("Base URL");
        baseUrlInput.setSingleLine(true);
        baseUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("選択中AIのAPI key（Keystoreで暗号化保存）");
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
        Button saveProvider = button("AIプロバイダ設定を保存", this::saveProviderSettings);
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
        phase.setText("\nPhase 4.1 — Multi AI Planner");
        phase.setTextSize(20);
        root.addView(phase);
        root.addView(providerStatus);
        root.addView(providerSpinner);
        root.addView(modelInput);
        root.addView(baseUrlInput);
        root.addView(apiKeyInput);
        root.addView(saveProvider);
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

        ProviderConfig.Type selected = providerStore.selectedType();
        providerSpinner.setSelection(selected.ordinal());
        loadProviderFields(selected);
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ProviderConfig.Type type = ProviderConfig.Type.values()[position];
                providerStore.select(type);
                apiKeyInput.setText("");
                loadProviderFields(type);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        shizuku.start();
        refreshProviderStatus();
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private ProviderConfig.Type selectedProvider() {
        int position = providerSpinner.getSelectedItemPosition();
        if (position < 0 || position >= ProviderConfig.Type.values().length) return ProviderConfig.Type.OPENAI;
        return ProviderConfig.Type.values()[position];
    }

    private ProviderConfig currentConfig() {
        return new ProviderConfig(
                selectedProvider(),
                modelInput.getText().toString(),
                baseUrlInput.getText().toString()
        );
    }

    private void loadProviderFields(ProviderConfig.Type type) {
        ProviderConfig config = providerStore.load(type);
        modelInput.setText(config.model);
        baseUrlInput.setText(config.baseUrl);
        refreshProviderStatus();
    }

    private void saveProviderSettings() {
        try {
            ProviderConfig config = currentConfig();
            config.validate();
            providerStore.save(config);

            String enteredKey = apiKeyInput.getText().toString().trim();
            if (!enteredKey.isEmpty()) {
                secrets.saveApiKey(config.id(), enteredKey);
                apiKeyInput.setText("");
            }
            refreshProviderStatus();
            Toast.makeText(this, config.type.label + " 設定を保存しました", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            providerStatus.setText("Provider: 保存失敗 " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void startAgent() {
        try {
            ProviderConfig config = currentConfig();
            config.validate();
            providerStore.save(config);

            String enteredKey = apiKeyInput.getText().toString().trim();
            if (!enteredKey.isEmpty()) {
                secrets.saveApiKey(config.id(), enteredKey);
                apiKeyInput.setText("");
            }

            String key = secrets.loadApiKey(config.id());
            if (key.isEmpty()) {
                agentStatus.setText("AI Agent: " + config.type.label + " APIキーが未設定です");
                return;
            }

            String goal = goalInput.getText().toString().trim();
            agentLog.setLength(0);
            output.setText("");
            refreshProviderStatus();
            agent.start(goal, config, key);
        } catch (Throwable t) {
            agentStatus.setText("AI Agent: 設定エラー " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void refreshProviderStatus() {
        if (providerStatus == null || providerSpinner == null || modelInput == null) return;
        ProviderConfig.Type type = selectedProvider();
        boolean configured;
        try { configured = secrets.hasApiKey(type.name().toLowerCase()); }
        catch (Throwable t) { configured = false; }
        String model = modelInput.getText() == null ? "" : modelInput.getText().toString().trim();
        providerStatus.setText("AI: " + type.label +
                " / key=" + (configured ? "configured" : "not configured") +
                " / model=" + (model.isEmpty() ? "(未設定)" : model));
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText("Task state: " + store.state() + " / attempt=" + store.attempt());
        refreshProviderStatus();
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
