package com.willcreations.harness;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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

public class MainActivity extends Activity implements
        ShizukuBridge.Listener,
        AgentRunner.Listener,
        ChatGptAuthManager.Listener {

    private TextView status;
    private TextView shizukuStatus;
    private TextView providerStatus;
    private TextView chatGptStatus;
    private TextView devStatus;
    private TextView agentStatus;
    private TextView output;
    private Spinner providerSpinner;
    private EditText modelInput;
    private EditText baseUrlInput;
    private EditText apiKeyInput;
    private EditText goalInput;
    private LinearLayout apiKeyControls;
    private LinearLayout chatGptControls;

    private TaskStore store;
    private SecretStore secrets;
    private ProviderStore providerStore;
    private ShizukuBridge shizuku;
    private ChatGptAuthManager chatGptAuth;
    private AgentRunner agent;

    private final StringBuilder agentLog = new StringBuilder();
    private String lastError = "";

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new TaskStore(this);
        secrets = new SecretStore(this);
        providerStore = new ProviderStore(this);
        shizuku = new ShizukuBridge(this, this);
        chatGptAuth = new ChatGptAuthManager(this, secrets, this);
        agent = new AgentRunner(this, shizuku, this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(36));

        status = text();
        shizukuStatus = text();
        providerStatus = text();
        chatGptStatus = text();
        devStatus = text();
        agentStatus = text();
        agentStatus.setText("AI Agent: idle");

        providerSpinner = new Spinner(this);
        String[] labels = new String[ProviderConfig.Type.values().length];
        for (int i = 0; i < labels.length; i++) labels[i] = ProviderConfig.Type.values()[i].label;
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(providerAdapter);

        modelInput = input("Model ID", false);
        baseUrlInput = input("Base URL", false);
        baseUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        apiKeyInput = input("選択中AIのAPI key（Keystoreで暗号化保存）", true);

        goalInput = new EditText(this);
        goalInput.setHint("AIにしてほしいこと");
        goalInput.setText("Bluetooth設定を開いて、Bluetooth画面に到達したことを確認して");
        goalInput.setMinLines(2);
        goalInput.setMaxLines(5);
        goalInput.setPadding(dp(12), dp(10), dp(12), dp(10));

        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setPadding(dp(14), dp(14), dp(14), dp(24));
        output.setMinHeight(dp(120));

        Button access = button("Accessibility設定", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button demo = button("固定自律テスト", () -> {
            store.set("WAIT_TARGET", 0);
            startActivity(new Intent(this, DemoTargetActivity.class));
        });
        Button connect = button("Shizuku接続・権限", shizuku::ensureConnected);
        Button packages = button("アプリ一覧（読取）", shizuku::listPackages);
        Button processes = button("プロセス一覧（読取）", shizuku::listProcesses);
        Button logs = button("Logcat（読取）", shizuku::readLogcat);

        Button saveProvider = button("AI設定を保存", this::saveProviderSettings);
        Button startAi = button("AIタスク開始", this::startAgent);
        Button stopAi = button("AIタスク停止", agent::cancel);

        Button copyError = button("エラーをコピー", this::copyLastError);
        Button copyLog = button("ログ全体をコピー", this::copyFullLog);

        Button openTermux = button("Termuxを開く", this::openTermux);
        Button termuxPermission = button("Termux実行権限の設定を開く", this::openRunCommandPermissionSettings);
        Button copyTermuxSetup = button("Termux初期設定コマンドをコピー", this::copyTermuxSetupCommand);
        Button devProbe = button("Termux環境チェック", () ->
                runDeveloperCapability("Developer environment", TermuxCommandBridge.Capability.ENV_PROBE));
        Button devSetup = button("開発Toolchainセットアップ", () ->
                runDeveloperCapability("Toolchain setup", TermuxCommandBridge.Capability.SETUP_TOOLCHAIN));
        Button sdkSetup = button("Android SDK 35セットアップ", () ->
                runDeveloperCapability("Android SDK setup", TermuxCommandBridge.Capability.SETUP_ANDROID_SDK));
        Button workspaceSetup = button("Workspace準備", () ->
                runDeveloperCapability("Workspace setup", TermuxCommandBridge.Capability.PREPARE_WORKSPACE));
        Button gitStatus = button("Git status", () ->
                runDeveloperCapability("Git status", TermuxCommandBridge.Capability.GIT_STATUS));
        Button gitDiff = button("Git diff", () ->
                runDeveloperCapability("Git diff", TermuxCommandBridge.Capability.GIT_DIFF));
        Button runTests = button("Unit tests", () ->
                runDeveloperCapability("Unit tests", TermuxCommandBridge.Capability.TESTS));
        Button buildApk = button("APKビルド", () ->
                runDeveloperCapability("APK build", TermuxCommandBridge.Capability.BUILD));

        apiKeyControls = new LinearLayout(this);
        apiKeyControls.setOrientation(LinearLayout.VERTICAL);
        add(apiKeyControls, apiKeyInput);

        chatGptControls = new LinearLayout(this);
        chatGptControls.setOrientation(LinearLayout.VERTICAL);
        Button signIn = button("ChatGPTでログイン", chatGptAuth::startSignIn);
        Button fetchModels = button("ChatGPT利用可能モデルを取得", chatGptAuth::listModels);
        Button signOut = button("ChatGPTログアウト", chatGptAuth::signOut);
        add(chatGptControls, chatGptStatus);
        add(chatGptControls, signIn);
        add(chatGptControls, fetchModels);
        add(chatGptControls, signOut);

        add(root, status);
        add(root, shizukuStatus);
        add(root, access);
        add(root, demo);
        add(root, connect);
        add(root, packages);
        add(root, processes);
        add(root, logs);

        TextView phase = text();
        phase.setText("Phase 4.2 — Multi AI + ChatGPT Login");
        phase.setTextSize(20);
        phase.setPadding(0, dp(16), 0, dp(8));
        add(root, phase);

        add(root, providerStatus);
        add(root, providerSpinner);
        add(root, modelInput);
        add(root, baseUrlInput);
        add(root, apiKeyControls);
        add(root, chatGptControls);
        add(root, saveProvider);
        add(root, goalInput);
        add(root, startAi);
        add(root, stopAi);
        add(root, agentStatus);
        add(root, copyError);
        add(root, copyLog);

        TextView phase5 = text();
        phase5.setText("Phase 5 — Phone-local Developer Worker");
        phase5.setTextSize(20);
        phase5.setPadding(0, dp(18), 0, dp(8));
        add(root, phase5);
        add(root, devStatus);
        add(root, openTermux);
        add(root, termuxPermission);
        add(root, copyTermuxSetup);
        add(root, devProbe);
        add(root, devSetup);
        add(root, sdkSetup);
        add(root, workspaceSetup);
        add(root, gitStatus);
        add(root, gitDiff);
        add(root, runTests);
        add(root, buildApk);

        add(root, output);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setPadding(0, 0, 0, dp(12));
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
                updateProviderModeUi(type);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        shizuku.start();
        updateProviderModeUi(selected);
        refreshProviderStatus();
        refreshChatGptStatus();
        refreshDeveloperStatus();
    }

    private TextView text() {
        TextView v = new TextView(this);
        v.setPadding(dp(4), dp(4), dp(4), dp(4));
        return v;
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        e.setInputType(InputType.TYPE_CLASS_TEXT |
                (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        return e;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void add(LinearLayout parent, View child) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(4), 0, dp(8));
        parent.addView(child, p);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private ProviderConfig.Type selectedProvider() {
        int position = providerSpinner.getSelectedItemPosition();
        if (position < 0 || position >= ProviderConfig.Type.values().length) {
            return ProviderConfig.Type.CHATGPT_LOGIN;
        }
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

    private void updateProviderModeUi(ProviderConfig.Type type) {
        boolean chatLogin = type == ProviderConfig.Type.CHATGPT_LOGIN;
        apiKeyControls.setVisibility(chatLogin ? View.GONE : View.VISIBLE);
        chatGptControls.setVisibility(chatLogin ? View.VISIBLE : View.GONE);
        baseUrlInput.setEnabled(!chatLogin);
        if (chatLogin) {
            baseUrlInput.setText("https://api.openai.com/v1");
            refreshChatGptStatus();
        }
        refreshProviderStatus();
    }

    private void saveProviderSettings() {
        try {
            ProviderConfig config = currentConfig();
            config.validate();
            providerStore.save(config);

            if (config.requiresApiKey()) {
                String enteredKey = apiKeyInput.getText().toString().trim();
                if (!enteredKey.isEmpty()) {
                    secrets.saveApiKey(config.id(), enteredKey);
                    apiKeyInput.setText("");
                }
            }
            clearError();
            refreshProviderStatus();
            Toast.makeText(this, config.type.label + " 設定を保存しました", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            setError("Provider設定エラー: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    private void startAgent() {
        try {
            ProviderConfig config = currentConfig();
            config.validate();
            providerStore.save(config);

            String goal = goalInput.getText().toString().trim();
            agentLog.setLength(0);
            output.setText("");
            clearError();

            if (config.usesChatGptLogin()) {
                if (!chatGptAuth.isSignedIn()) {
                    setError("ChatGPTにログインしてからAIタスクを開始してください");
                    return;
                }
                agent.startWithChatGpt(goal, config, chatGptAuth);
            } else {
                String enteredKey = apiKeyInput.getText().toString().trim();
                if (!enteredKey.isEmpty()) {
                    secrets.saveApiKey(config.id(), enteredKey);
                    apiKeyInput.setText("");
                }

                String key = secrets.loadApiKey(config.id());
                if (key.isEmpty()) {
                    setError(config.type.label + " APIキーが未設定です");
                    return;
                }
                agent.start(goal, config, key);
            }

            refreshProviderStatus();
        } catch (Throwable t) {
            setError("AI設定エラー: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
    }

    private void refreshProviderStatus() {
        if (providerStatus == null || providerSpinner == null || modelInput == null) return;
        ProviderConfig.Type type = selectedProvider();
        String model = modelInput.getText() == null ? "" : modelInput.getText().toString().trim();

        if (type == ProviderConfig.Type.CHATGPT_LOGIN) {
            providerStatus.setText("AI: ChatGPTログイン / " +
                    (chatGptAuth != null && chatGptAuth.isSignedIn() ? "connected" : "not connected") +
                    " / model=" + (model.isEmpty() ? "(未設定)" : model));
        } else {
            boolean configured;
            try { configured = secrets.hasApiKey(type.name().toLowerCase()); }
            catch (Throwable t) { configured = false; }
            providerStatus.setText("AI: " + type.label +
                    " / key=" + (configured ? "configured" : "not configured") +
                    " / model=" + (model.isEmpty() ? "(未設定)" : model));
        }
    }

    private void refreshChatGptStatus() {
        if (chatGptStatus == null || chatGptAuth == null) return;
        chatGptStatus.setText(chatGptAuth.isSignedIn()
                ? "ChatGPT: connected — " + chatGptAuth.accountLabel()
                : "ChatGPT: not connected");
    }

    private void refreshDeveloperStatus() {
        if (devStatus == null) return;
        boolean installed = TermuxCommandBridge.isInstalled(this);
        boolean permission = checkSelfPermission("com.termux.permission.RUN_COMMAND")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        devStatus.setText("Developer runtime: Termux=" + (installed ? "installed" : "not installed")
                + " / RUN_COMMAND=" + (permission ? "granted" : "not granted"));
    }

    private void openTermux() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.termux");
        if (launch == null) {
            setError("Termuxがインストールされていません");
            return;
        }
        startActivity(launch);
    }

    private void openRunCommandPermissionSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this,
                    "権限 → 追加の権限 →「Run commands in Termux environment」を許可してください",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            setError("権限設定を開けません: " + safeMessage(t));
        }
    }

    private void copyTermuxSetupCommand() {
        String command = "mkdir -p ~/.termux; " +
                "touch ~/.termux/termux.properties; " +
                "grep -q '^allow-external-apps *= *true' ~/.termux/termux.properties || " +
                "echo 'allow-external-apps=true' >> ~/.termux/termux.properties; " +
                "termux-reload-settings";
        copyToClipboard("Will Harness Termux setup", command);
        Toast.makeText(this,
                "コピーしました。Termuxを開いて貼り付け、Enterしてください",
                Toast.LENGTH_LONG).show();
    }

    private void runDeveloperCapability(String title, TermuxCommandBridge.Capability capability) {
        devStatus.setText("Developer: " + title + " 実行中…");
        new Thread(() -> {
            ToolResult result = TermuxCommandBridge.run(getApplicationContext(), capability);
            runOnUiThread(() -> {
                devStatus.setText("Developer: " + title + " / " + (result.ok ? "PASS" : "FAIL"));
                output.setText("[" + title + "]\n" + result.message);
                if (!result.ok) lastError = result.message;
                refreshDeveloperStatus();
            });
        }, "will-harness-developer").start();
    }

    private void copyLastError() {
        if (lastError == null || lastError.trim().isEmpty()) {
            Toast.makeText(this, "コピーするエラーはありません", Toast.LENGTH_SHORT).show();
            return;
        }
        copyToClipboard("Will Harness error", lastError);
        Toast.makeText(this, "エラーをコピーしました", Toast.LENGTH_SHORT).show();
    }

    private void copyFullLog() {
        String text = output.getText() == null ? "" : output.getText().toString();
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "コピーするログはありません", Toast.LENGTH_SHORT).show();
            return;
        }
        copyToClipboard("Will Harness log", text);
        Toast.makeText(this, "ログをコピーしました", Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void setError(String error) {
        lastError = error == null ? "" : error.trim();
        agentStatus.setText("AI Agent: " + lastError);
        if (!lastError.isEmpty()) {
            agentLog.append("ERROR: ").append(lastError).append('\n');
            output.setText("[Agent]\n" + agentLog);
        }
    }

    private void clearError() {
        lastError = "";
    }

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m;
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText("Task state: " + store.state() + " / attempt=" + store.attempt());
        refreshProviderStatus();
        refreshChatGptStatus();
        refreshDeveloperStatus();
    }

    @Override
    protected void onDestroy() {
        if (agent != null) agent.shutdown();
        if (chatGptAuth != null) chatGptAuth.shutdown();
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
        if (state != null && state.startsWith("AI_FAILED:")) {
            lastError = state.substring("AI_FAILED:".length()).trim();
        }
    }

    @Override
    public void onAgentLog(String line) {
        if (agentLog.length() > 12000) {
            agentLog.delete(0, agentLog.length() - 9000);
        }
        agentLog.append(line).append('\n');
        if (line != null && (line.startsWith("FAIL:") || line.startsWith("ERROR:") || line.contains(" HTTP 4") || line.contains(" HTTP 5"))) {
            lastError = line;
        }
        output.setText("[Agent]\n" + agentLog);
    }

    @Override
    public void onChatGptAuthState(String state) {
        refreshChatGptStatus();
        refreshProviderStatus();
        if (state != null && (state.contains("失敗") || state.contains("エラー") || state.contains("必要"))) {
            lastError = state;
        }
        Toast.makeText(this, state, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onChatGptModels(String firstSlug, String displayText) {
        if (selectedProvider() != ProviderConfig.Type.CHATGPT_LOGIN) return;
        if (modelInput.getText().toString().trim().isEmpty() && firstSlug != null) {
            modelInput.setText(firstSlug);
        }
        output.setText("[ChatGPT models]\n" + displayText);
        Toast.makeText(this, "利用可能モデルを取得しました", Toast.LENGTH_SHORT).show();
    }
}
