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
        SETUP_ANDROID_SDK,
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

            long timeoutSeconds =
                    (capability == Capability.SETUP_TOOLCHAIN || capability == Capability.SETUP_ANDROID_SDK) ? 900 :
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
                        "for c in git java javac gradle aapt aapt2 aidl zipalign d8 apksigner adb; do " +
                        "printf '%s=' \"$c\"; command -v \"$c\" || echo MISSING; done; " +
                        "git --version 2>/dev/null || true; " +
                        "java -version 2>&1 | head -n 2 || true; " +
                        "gradle --version 2>/dev/null | head -n 8 || true; " +
                        "aapt2 version 2>/dev/null || true; " +
                        "test -f \"$HOME/Android/Sdk/platforms/android-35/android.jar\" && echo SDK35=READY || echo SDK35=MISSING; " +
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
                        "pkg install -y git openjdk-21 gradle aapt aapt2 aidl apksigner d8 android-tools curl unzip; " +
                        "echo 'TOOLCHAIN_PACKAGES_INSTALLED'; " +
                        "git --version; java -version 2>&1 | head -n 2; gradle --version | head -n 8; " +
                        "aapt2 version; aidl --version 2>&1 | head -n 2 || true; adb version | head -n 2";
            case SETUP_ANDROID_SDK:
                return "set -eu; " +
                        "ANDROID_HOME=\"$HOME/Android/Sdk\"; CACHE=\"$HOME/.cache/will-harness\"; " +
                        "mkdir -p \"$ANDROID_HOME\" \"$CACHE\"; " +
                        "TOOLS=\"$CACHE/commandlinetools-linux-9123335_latest.zip\"; " +
                        "if [ ! -x \"$ANDROID_HOME/cmdline-tools/bin/sdkmanager\" ] && [ ! -x \"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" ]; then " +
                        "curl -fL --retry 3 --connect-timeout 30 " +
                        "https://dl.google.com/android/repository/commandlinetools-linux-9123335_latest.zip -o \"$TOOLS\"; " +
                        "echo '0bebf59339eaa534f4217f8aa0972d14dc49e7207be225511073c661ae01da0a  '\"$TOOLS\" | sha256sum -c -; " +
                        "rm -rf \"$ANDROID_HOME/cmdline-tools\"; unzip -q \"$TOOLS\" -d \"$ANDROID_HOME\"; fi; " +
                        "if [ -x \"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" ]; then SDKMANAGER=\"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"; " +
                        "else SDKMANAGER=\"$ANDROID_HOME/cmdline-tools/bin/sdkmanager\"; fi; " +
                        "yes | \"$SDKMANAGER\" --sdk_root=\"$ANDROID_HOME\" --licenses >/dev/null || true; " +
                        "yes | \"$SDKMANAGER\" --sdk_root=\"$ANDROID_HOME\" \"platforms;android-35\" \"build-tools;35.0.0\"; " +
                        "BT=\"$ANDROID_HOME/build-tools/35.0.0\"; " +
                        "for t in aapt aapt2 aidl zipalign; do if command -v \"$t\" >/dev/null 2>&1; then " +
                        "if [ -e \"$BT/$t\" ] && [ ! -e \"$BT/$t.google\" ]; then mv \"$BT/$t\" \"$BT/$t.google\"; fi; " +
                        "cp \"$(command -v \"$t\")\" \"$BT/$t\"; chmod 700 \"$BT/$t\"; fi; done; " +
                        "mkdir -p \"$HOME/.gradle\"; " +
                        "PROP=\"$HOME/.gradle/gradle.properties\"; touch \"$PROP\"; " +
                        "grep -q '^android.aapt2FromMavenOverride=' \"$PROP\" && " +
                        "sed -i 's|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride='\"$PREFIX\"'/bin/aapt2|' \"$PROP\" || " +
                        "echo 'android.aapt2FromMavenOverride='\"$PREFIX\"'/bin/aapt2' >> \"$PROP\"; " +
                        "if [ -d " + quote(PROJECT_ROOT) + " ]; then echo 'sdk.dir='\"$ANDROID_HOME\" > " + quote(PROJECT_ROOT + "/local.properties") + "; fi; " +
                        "test -f \"$ANDROID_HOME/platforms/android-35/android.jar\"; " +
                        "echo 'ANDROID_SDK_READY'; echo ANDROID_HOME=\"$ANDROID_HOME\"; ls -lh \"$ANDROID_HOME/platforms/android-35/android.jar\"";
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
            case SETUP_TOOLCHAIN: return "Installs the fixed ARM-native developer toolchain packages inside Termux.";
            case SETUP_ANDROID_SDK: return "Installs Android SDK 35 and overlays ARM-native Android build tools.";
            case GIT_STATUS: return "Reads git status in the fixed Will Harness workspace.";
            case GIT_DIFF: return "Reads git diff in the fixed Will Harness workspace.";
            case TESTS: return "Runs unit tests for the fixed Will Harness Android project.";
            case BUILD: return "Builds a debug APK for the fixed Will Harness Android project.";
            default: return "Will Harness developer capability.";
        }
    }
}
