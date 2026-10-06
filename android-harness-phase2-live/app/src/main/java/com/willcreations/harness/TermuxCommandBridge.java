package com.willcreations.harness;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TermuxCommandBridge {
    public enum Capability {
        ENV_PROBE,
        PREPARE_WORKSPACE,
        SETUP_TOOLCHAIN,
        GIT_STATUS,
        GIT_DIFF,
        TESTS,
        BUILD
    }

    private static final AtomicInteger NEXT_ID = new AtomicInteger(5000);
    private static final ConcurrentHashMap<Integer, CompletableFuture<ToolResult>> PENDING = new ConcurrentHashMap<>();
    private static final String REPO_ROOT = "$HOME/will-harness-workspace/codex-chatgpt-web";
    private static final String PROJECT_ROOT = REPO_ROOT + "/android-harness-phase2-live";

    private TermuxCommandBridge() {}

    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TermuxConstants.TERMUX_PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static ToolResult run(Context context, Capability capability) {
        if (!isInstalled(context)) {
            return ToolResult.fail("Termux is not installed");
        }

        String script = scriptFor(capability);
        if (script == null || script.isEmpty()) return ToolResult.fail("Unsupported developer capability");

        int executionId = NEXT_ID.incrementAndGet();
        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        PENDING.put(executionId, future);

        try {
            Intent resultIntent = new Intent(context, TermuxResultService.class);
            resultIntent.putExtra(TermuxResultService.EXTRA_EXECUTION_ID, executionId);

            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;

            PendingIntent pendingIntent = PendingIntent.getService(
                    context,
                    executionId,
                    resultIntent,
                    flags
            );

            Intent intent = new Intent();
            intent.setClassName(
                    TermuxConstants.TERMUX_PACKAGE_NAME,
                    TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE_NAME
            );
            intent.setAction(RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND);
            intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, "$PREFIX/bin/bash");
            intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS, new String[]{"-lc", script});
            intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_WORKDIR, "~/");
            intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, true);
            intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_LABEL, "Will Harness: " + capability.name());
            intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_DESCRIPTION, description(capability));
            intent.putExtra(RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT, pendingIntent);

            context.startService(intent);

            long timeoutSeconds = capability == Capability.SETUP_TOOLCHAIN ? 600 :
                    (capability == Capability.BUILD || capability == Capability.TESTS ? 300 : 90);

            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return ToolResult.fail("Termux command timed out: " + capability.name());
        } catch (SecurityException e) {
            return ToolResult.fail("Termux permission denied. Grant 'Run commands in Termux environment' and enable allow-external-apps=true");
        } catch (Throwable t) {
            return ToolResult.fail("Termux bridge error: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        } finally {
            PENDING.remove(executionId);
        }
    }

    static void deliver(int executionId, Intent intent) {
        CompletableFuture<ToolResult> future = PENDING.get(executionId);
        if (future == null) return;

        Bundle result = intent == null ? null : intent.getBundleExtra(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE);
        if (result == null) {
            future.complete(ToolResult.fail("Termux result bundle missing"));
            return;
        }

        String stdout = result.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, "");
        String stderr = result.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR, "");
        int exitCode = result.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, -1);
        int err = result.getInt(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR, 0);
        String errMsg = result.getString(TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG, "");

        StringBuilder text = new StringBuilder();
        text.append("exit=").append(exitCode).append('\n');
        if (stdout != null && !stdout.isEmpty()) text.append(stdout);
        if (stderr != null && !stderr.isEmpty()) text.append("\n[stderr]\n").append(stderr);
        if (err != 0 || (errMsg != null && !errMsg.isEmpty())) {
            text.append("\n[termux-error] code=").append(err).append(' ')
                    .append(errMsg == null ? "" : errMsg);
        }

        String output = text.toString();
        if (output.length() > 16000) output = output.substring(output.length() - 16000);

        if (exitCode == 0 && err == 0) future.complete(ToolResult.ok(output));
        else future.complete(ToolResult.fail(output));
    }

    private static String scriptFor(Capability capability) {
        switch (capability) {
            case ENV_PROBE:
                return "set +e; " +
                        "echo 'PHASE5_ENV'; " +
                        "echo ARCH=$(uname -m); echo PREFIX=$PREFIX; " +
                        "for c in git java javac gradle aapt2 apksigner adb; do " +
                        "printf '%s=' \"$c\"; command -v \"$c\" || echo MISSING; done; " +
                        "git --version 2>/dev/null || true; " +
                        "java -version 2>&1 | head -n 2 || true; " +
                        "gradle --version 2>/dev/null | head -n 8 || true; " +
                        "aapt2 version 2>/dev/null || true; " +
                        "test -d " + quote(REPO_ROOT) + " && echo WORKSPACE=READY || echo WORKSPACE=MISSING";
            case PREPARE_WORKSPACE:
                return "set -eu; mkdir -p \"$HOME/will-harness-workspace\"; " +
                        "cd \"$HOME/will-harness-workspace\"; " +
                        "if [ ! -d codex-chatgpt-web/.git ]; then " +
                        "git clone --single-branch --branch android-harness-phase2-build " +
                        "https://github.com/willcreations0113-arch/codex-chatgpt-web.git codex-chatgpt-web; " +
                        "else echo 'workspace already exists'; fi; " +
                        "cd codex-chatgpt-web; git status --short --branch";
            case SETUP_TOOLCHAIN:
                return "set -eu; " +
                        "pkg update -y; " +
                        "pkg install -y git openjdk-21 gradle aapt2 apksigner curl unzip; " +
                        "echo 'TOOLCHAIN_PACKAGES_INSTALLED'; " +
                        "git --version; java -version 2>&1 | head -n 2; gradle --version | head -n 8; aapt2 version";
            case GIT_STATUS:
                return "set -eu; cd " + quote(REPO_ROOT) + "; git status --short --branch";
            case GIT_DIFF:
                return "set -eu; cd " + quote(REPO_ROOT) + "; git diff --no-ext-diff -- . ':!*.apk' | head -c 15000";
            case TESTS:
                return "set -eu; cd " + quote(PROJECT_ROOT) + "; " +
                        "export ANDROID_HOME=\"$HOME/Android/Sdk\"; export ANDROID_SDK_ROOT=\"$ANDROID_HOME\"; " +
                        "gradle --no-daemon :app:testDebugUnitTest -Pandroid.aapt2FromMavenOverride=\"$PREFIX/bin/aapt2\"";
            case BUILD:
                return "set -eu; cd " + quote(PROJECT_ROOT) + "; " +
                        "export ANDROID_HOME=\"$HOME/Android/Sdk\"; export ANDROID_SDK_ROOT=\"$ANDROID_HOME\"; " +
                        "gradle --no-daemon :app:assembleDebug -Pandroid.aapt2FromMavenOverride=\"$PREFIX/bin/aapt2\"; " +
                        "APK=app/build/outputs/apk/debug/app-debug.apk; test -f \"$APK\"; " +
                        "echo APK=$PWD/$APK; sha256sum \"$APK\"; ls -lh \"$APK\"";
            default:
                return null;
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String description(Capability capability) {
        switch (capability) {
            case ENV_PROBE: return "Checks the phone-local developer environment.";
            case PREPARE_WORKSPACE: return "Creates the fixed Will Harness workspace and clones the allowed repository.";
            case SETUP_TOOLCHAIN: return "Installs the fixed developer toolchain packages inside Termux.";
            case GIT_STATUS: return "Reads git status in the fixed Will Harness workspace.";
            case GIT_DIFF: return "Reads git diff in the fixed Will Harness workspace.";
            case TESTS: return "Runs unit tests for the fixed Will Harness Android project.";
            case BUILD: return "Builds a debug APK for the fixed Will Harness Android project.";
            default: return "Will Harness developer capability.";
        }
    }
}
