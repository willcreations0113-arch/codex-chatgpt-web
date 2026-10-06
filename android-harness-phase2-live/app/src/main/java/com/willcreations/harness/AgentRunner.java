package com.willcreations.harness;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentRunner {
    public interface Listener {
        void onAgentState(String state);
        void onAgentLog(String line);
    }

    private interface CredentialProvider {
        String get() throws Exception;
    }

    private static final int MAX_STEPS = 8;

    private final TaskStore store;
    private final ProviderPlanner planner = new ProviderPlanner();
    private final AgentPolicy policy = new AgentPolicy();
    private final AgentVerifier verifier = new AgentVerifier();
    private final ToolRegistry tools;
    private final ShizukuBridge shizuku;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AgentRunner(Context context, ShizukuBridge shizuku, Listener listener) {
        Context app = context.getApplicationContext();
        this.store = new TaskStore(app);
        this.shizuku = shizuku;
        this.tools = new ToolRegistry(app, shizuku);
        this.listener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start(String goal, ProviderConfig config, String apiKey) {
        startInternal(goal, config, () -> apiKey);
    }

    public void startWithChatGpt(String goal, ProviderConfig config, ChatGptAuthManager auth) {
        if (auth == null) {
            emitState("AI_FAILED: ChatGPT認証マネージャーがありません");
            return;
        }
        startInternal(goal, config, auth::getValidAccessToken);
    }

    private void startInternal(String goal, ProviderConfig config, CredentialProvider credentials) {
        String normalizedGoal = goal == null ? "" : goal.trim();
        if (normalizedGoal.isEmpty()) {
            emitState("AI_FAILED: 目的を入力してください");
            return;
        }
        if (config == null) {
            emitState("AI_FAILED: AIプロバイダ設定がありません");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            emitState("AIはすでに実行中です");
            return;
        }
        cancelled.set(false);
        worker.execute(() -> runLoop(normalizedGoal, config, credentials));
    }

    public void cancel() {
        cancelled.set(true);
        emitLog("STOP requested");
    }

    public void shutdown() {
        cancelled.set(true);
        worker.shutdownNow();
    }

    private void runLoop(String goal, ProviderConfig config, CredentialProvider credentials) {
        StringBuilder history = new StringBuilder();
        try {
            config.validate();
            if (!HarnessAccessibilityService.isReady()) {
                fail("Accessibilityが接続されていません。先にAccessibility設定をONにしてください", 0);
                return;
            }
            history.append("system: shizuku_connected=")
                    .append(shizuku != null && shizuku.isPrivilegedConnected())
                    .append('\n');
            store.set("AI_RUNNING", 0);
            emitState("AI_RUNNING");
            emitLog("PROVIDER: " + config.type.label + " / model=" + config.model);
            emitLog("GOAL: " + goal);

            int policyDenials = 0;
            for (int step = 1; step <= MAX_STEPS; step++) {
                if (cancelled.get()) {
                    store.set("AI_CANCELLED", step - 1);
                    emitState("AI_CANCELLED");
                    return;
                }

                UiSnapshot before = HarnessAccessibilityService.observeAllowedUi();
                emitLog("STEP " + step + " observe package=" + before.packageName);
                if (!before.allowed) {
                    fail("操作対象外の画面です: " + before.packageName, step);
                    return;
                }

                String credential = credentials.get();
                if (credential == null || credential.trim().isEmpty()) {
                    fail(config.usesChatGptLogin()
                            ? "ChatGPTログインが必要です"
                            : config.type.label + " APIキーが未設定です", step);
                    return;
                }

                AgentAction action = planner.plan(config, credential, goal, before, history.toString());
                emitLog("PLAN: " + action + " / " + action.rationale);

                AgentPolicy.Decision decision = policy.check(action, before);
                if (!decision.allowed) {
                    policyDenials++;
                    String denied = "POLICY_DENIED: " + decision.reason;
                    emitLog(denied);
                    appendHistory(history, step, action, denied);
                    if (policyDenials >= 2) {
                        fail("Policyで2回拒否しました: " + decision.reason, step);
                        return;
                    }
                    continue;
                }

                if (action.type == AgentAction.Type.FINISH) {
                    AgentVerifier.Verification verification = verifier.verifyFinish(action, before);
                    emitLog("VERIFY: " + verification.message);
                    if (verification.passed) {
                        store.set("AI_PASSED", step);
                        emitState("AI_PASSED: " + (action.message.isEmpty() ? verification.message : action.message));
                        return;
                    }
                    appendHistory(history, step, action, "FINISH_REJECTED: " + verification.message);
                    continue;
                }

                if (action.type == AgentAction.Type.FAIL) {
                    fail(action.message.isEmpty() ? "Planner judged the goal unsafe or impossible" : action.message, step);
                    return;
                }

                ToolResult result = tools.execute(action);
                String safeResult = result.message == null ? "" : result.message;
                if (safeResult.length() > 4500) safeResult = safeResult.substring(0, 4500) + "…";
                emitLog("TOOL: " + (result.ok ? "OK " : "FAIL ") + safeResult);
                appendHistory(history, step, action, safeResult);
                store.set("AI_RUNNING", step);

                if (action.type == AgentAction.Type.WAIT) {
                    Thread.sleep(1500);
                } else {
                    Thread.sleep(1100);
                }
            }
            fail("最大ステップ数 " + MAX_STEPS + " に到達しました", MAX_STEPS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.set("AI_CANCELLED", store.attempt());
            emitState("AI_CANCELLED");
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + t.getMessage(), store.attempt());
        } finally {
            running.set(false);
        }
    }

    private void appendHistory(StringBuilder history, int step, AgentAction action, String result) {
        history.append("step=").append(step)
                .append(" action=").append(action)
                .append(" result=").append(result == null ? "" : result.replace('\n', ' '))
                .append('\n');
        if (history.length() > 6000) {
            history.delete(0, history.length() - 5000);
        }
    }

    private void fail(String message, int attempt) {
        store.set("AI_FAILED", attempt);
        emitState("AI_FAILED: " + message);
        emitLog("FAIL: " + message);
    }

    private void emitState(String state) {
        main.post(() -> listener.onAgentState(state));
    }

    private void emitLog(String line) {
        main.post(() -> listener.onAgentLog(line));
    }
}
