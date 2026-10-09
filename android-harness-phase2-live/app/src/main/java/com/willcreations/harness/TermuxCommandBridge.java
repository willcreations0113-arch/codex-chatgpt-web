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
        SETUP_PHASE5A,
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
                    capability == Capability.SETUP_PHASE5A ? 1800 :
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
                        "ANDROID_HOME=\"$HOME/Android/Sdk\"; PINNED_GRADLE=\"$HOME/.local/share/will-harness/gradle-8.10.2/bin/gradle\"; " +
                        "echo 'PHASE5A_ENV'; " +
                        "echo ARCH=$(uname -m); echo PREFIX=$PREFIX; echo ANDROID_HOME=$ANDROID_HOME; " +
                        "if grep -Eq '^allow-external-apps[[:space:]]*=[[:space:]]*true' \"$HOME/.termux/termux.properties\" 2>/dev/null; then echo ALLOW_EXTERNAL_APPS=READY; else echo ALLOW_EXTERNAL_APPS=MISSING; fi; " +
                        "for c in git java javac aapt aapt2 aidl zipalign d8 apksigner adb; do " +
                        "printf '%s=' \"$c\"; command -v \"$c\" || echo MISSING; done; " +
                        "git --version 2>/dev/null || true; " +
                        "java -version 2>&1 | head -n 2 || true; " +
                        "if [ -x \"$PINNED_GRADLE\" ]; then \"$PINNED_GRADLE\" --version 2>/dev/null | head -n 8; echo GRADLE_8_10_2=READY; else echo GRADLE_8_10_2=MISSING; fi; " +
                        "aapt2 version 2>/dev/null || true; " +
                        "test -f \"$ANDROID_HOME/platforms/android-35/android.jar\" && echo SDK35=READY || echo SDK35=MISSING; " +
                        "test -x \"$ANDROID_HOME/build-tools/35.0.0/aapt2\" && echo BUILD_TOOLS_ARM=READY || echo BUILD_TOOLS_ARM=MISSING; " +
                        "test -d " + shellPath(REPO_ROOT) + " && echo WORKSPACE=READY || echo WORKSPACE=MISSING";
            case PREPARE_WORKSPACE:
                return "set -eu; mkdir -p \"$HOME/will-harness-workspace\"; " +
                        "cd \"$HOME/will-harness-workspace\"; " +
                        "if [ ! -d codex-chatgpt-web/.git ]; then " +
                        "git clone --single-branch --branch android-harness-phase2-build " +
                        "https://github.com/willcreations0113-arch/codex-chatgpt-web.git codex-chatgpt-web; " +
                        "else cd codex-chatgpt-web; " +
                        "if git diff --quiet && git diff --cached --quiet; then " +
                        "git fetch origin android-harness-phase2-build; git checkout android-harness-phase2-build; git pull --ff-only; " +
                        "else echo 'WORKSPACE_DIRTY_NOT_UPDATED'; fi; cd ..; fi; " +
                        "cd codex-chatgpt-web; git status --short --branch";
            case SETUP_TOOLCHAIN:
                return "set -eu; " +
                        "pkg update -y; " +
                        "pkg install -y git openjdk-21 gradle aapt apksigner d8 android-tools curl unzip coreutils; " +
                        "for c in git java javac aapt aapt2 aidl zipalign d8 apksigner adb curl unzip sha256sum; do " +
                        "command -v \"$c\" >/dev/null 2>&1 || { echo TOOL_MISSING:$c; exit 21; }; done; " +
                        "echo 'TOOLCHAIN_PACKAGES_INSTALLED'; " +
                        "git --version; java -version 2>&1 | head -n 2; " +
                        "aapt2 version; adb version | head -n 2";
            case SETUP_ANDROID_SDK:
                return "set -eu; " +
                        "ANDROID_HOME=\"$HOME/Android/Sdk\"; CACHE=\"$HOME/.cache/will-harness\"; " +
                        "mkdir -p \"$ANDROID_HOME/cmdline-tools\" \"$CACHE\" \"$HOME/.local/share/will-harness\"; " +
                        "TOOLS=\"$CACHE/commandlinetools-linux-15859902_latest.zip\"; " +
                        "if [ ! -f \"$TOOLS\" ]; then curl -fL --retry 3 --connect-timeout 30 " +
                        "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o \"$TOOLS\"; fi; " +
                        "echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  '\"$TOOLS\" | sha256sum -c -; " +
                        "if [ ! -x \"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" ]; then " +
                        "rm -rf \"$CACHE/cmdline-extract\" \"$ANDROID_HOME/cmdline-tools/latest\"; " +
                        "mkdir -p \"$CACHE/cmdline-extract\"; unzip -q \"$TOOLS\" -d \"$CACHE/cmdline-extract\"; " +
                        "mv \"$CACHE/cmdline-extract/cmdline-tools\" \"$ANDROID_HOME/cmdline-tools/latest\"; fi; " +
                        "SDKMANAGER=\"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"; " +
                        "yes | \"$SDKMANAGER\" --sdk_root=\"$ANDROID_HOME\" --licenses >/dev/null || true; " +
                        "yes | \"$SDKMANAGER\" --sdk_root=\"$ANDROID_HOME\" \"platforms;android-35\" \"build-tools;35.0.0\"; " +
                        "BT=\"$ANDROID_HOME/build-tools/35.0.0\"; " +
                        "for t in aapt aapt2 aidl zipalign; do " +
                        "NATIVE=$(command -v \"$t\" || true); [ -n \"$NATIVE\" ] || { echo ARM_TOOL_MISSING:$t; exit 22; }; " +
                        "if [ -e \"$BT/$t\" ] && [ ! -e \"$BT/$t.google-x86_64\" ]; then mv \"$BT/$t\" \"$BT/$t.google-x86_64\"; fi; " +
                        "cp \"$NATIVE\" \"$BT/$t\"; chmod 700 \"$BT/$t\"; done; " +
                        "GRADLE_ZIP=\"$CACHE/gradle-8.10.2-bin.zip\"; GRADLE_ROOT=\"$HOME/.local/share/will-harness/gradle-8.10.2\"; " +
                        "if [ ! -x \"$GRADLE_ROOT/bin/gradle\" ]; then " +
                        "if [ ! -f \"$GRADLE_ZIP\" ]; then curl -fL --retry 3 --connect-timeout 30 " +
                        "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip -o \"$GRADLE_ZIP\"; fi; " +
                        "echo '31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26  '\"$GRADLE_ZIP\" | sha256sum -c -; " +
                        "rm -rf \"$GRADLE_ROOT\"; unzip -q \"$GRADLE_ZIP\" -d \"$HOME/.local/share/will-harness\"; fi; " +
                        "mkdir -p \"$HOME/.gradle\"; PROP=\"$HOME/.gradle/gradle.properties\"; touch \"$PROP\"; " +
                        "grep -q '^android.aapt2FromMavenOverride=' \"$PROP\" && " +
                        "sed -i 's|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride='\"$PREFIX\"'/bin/aapt2|' \"$PROP\" || " +
                        "echo 'android.aapt2FromMavenOverride='\"$PREFIX\"'/bin/aapt2' >> \"$PROP\"; " +
                        "if [ -d " + shellPath(PROJECT_ROOT) + " ]; then echo 'sdk.dir='\"$ANDROID_HOME\" > " + shellPath(PROJECT_ROOT + "/local.properties") + "; fi; " +
                        "test -f \"$ANDROID_HOME/platforms/android-35/android.jar\"; " +
                        "test -x \"$BT/aapt2\"; test -x \"$GRADLE_ROOT/bin/gradle\"; " +
                        "echo 'ANDROID_SDK_READY'; echo ANDROID_HOME=\"$ANDROID_HOME\"; \"$GRADLE_ROOT/bin/gradle\" --version | head -n 8";
            case SETUP_PHASE5A:
                return scriptFor(Capability.SETUP_TOOLCHAIN) + "; " +
                        scriptFor(Capability.PREPARE_WORKSPACE) + "; " +
                        scriptFor(Capability.SETUP_ANDROID_SDK);
            case GIT_STATUS:
                return "set -eu; cd " + shellPath(REPO_ROOT) + "; git status --short --branch";
            case GIT_DIFF:
                return "set -eu; cd " + shellPath(REPO_ROOT) + "; git diff --no-ext-diff -- . ':!*.apk' | head -c 15000";
            case TESTS:
                return "set -eu; " +
                        "[ -d " + shellPath(PROJECT_ROOT) + " ] || { echo 'WORKSPACE_NOT_READY: Run Workspace準備 or Phase 5A setup.'; exit 30; }; " +
                        "cd " + shellPath(PROJECT_ROOT) + "; " +
                        "export ANDROID_HOME=\"$HOME/Android/Sdk\"; export ANDROID_SDK_ROOT=\"$ANDROID_HOME\"; " +
                        "GRADLE=\"$HOME/.local/share/will-harness/gradle-8.10.2/bin/gradle\"; " +
                        "[ -f \"$ANDROID_HOME/platforms/android-35/android.jar\" ] || { echo 'SDK_NOT_READY: Android SDK 35 missing. Run Phase 5A setup.'; exit 31; }; " +
                        "[ -x \"$PREFIX/bin/aapt2\" ] || { echo 'AAPT2_NOT_READY: Run Phase 5A setup.'; exit 32; }; " +
                        "[ -x \"$GRADLE\" ] || { echo 'GRADLE_NOT_READY: Gradle 8.10.2 missing. Run Phase 5A setup.'; exit 33; }; " +
                        "\"$GRADLE\" --no-daemon :app:testDebugUnitTest -Pandroid.aapt2FromMavenOverride=\"$PREFIX/bin/aapt2\"";
            case BUILD:
                return "set -eu; " +
                        "[ -d " + shellPath(PROJECT_ROOT) + " ] || { echo 'WORKSPACE_NOT_READY: Run Workspace準備 or Phase 5A setup.'; exit 30; }; " +
                        "cd " + shellPath(PROJECT_ROOT) + "; " +
                        "export ANDROID_HOME=\"$HOME/Android/Sdk\"; export ANDROID_SDK_ROOT=\"$ANDROID_HOME\"; " +
                        "GRADLE=\"$HOME/.local/share/will-harness/gradle-8.10.2/bin/gradle\"; " +
                        "[ -f \"$ANDROID_HOME/platforms/android-35/android.jar\" ] || { echo 'SDK_NOT_READY: Android SDK 35 missing. Run Phase 5A setup.'; exit 31; }; " +
                        "[ -x \"$PREFIX/bin/aapt2\" ] || { echo 'AAPT2_NOT_READY: Run Phase 5A setup.'; exit 32; }; " +
                        "[ -x \"$GRADLE\" ] || { echo 'GRADLE_NOT_READY: Gradle 8.10.2 missing. Run Phase 5A setup.'; exit 33; }; " +
                        "\"$GRADLE\" --no-daemon :app:assembleDebug -Pandroid.aapt2FromMavenOverride=\"$PREFIX/bin/aapt2\"; " +
                        "APK=app/build/outputs/apk/debug/app-debug.apk; test -f \"$APK\"; " +
                        "echo APK=$PWD/$APK; sha256sum \"$APK\"; ls -lh \"$APK\"";
            default:
                return null;
        }
    }

    private static String shellPath(String value) {
        if (value.startsWith("$HOME/")) {
            return "\"$HOME/" + value.substring("$HOME/".length()).replace("\"", "\\\"") + "\"";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String description(Capability capability) {
        switch (capability) {
            case ENV_PROBE: return "Checks the phone-local developer environment.";
            case PREPARE_WORKSPACE: return "Creates the fixed Will Harness workspace and clones the allowed repository.";
            case SETUP_TOOLCHAIN: return "Installs the fixed ARM-native developer toolchain packages inside Termux.";
            case SETUP_ANDROID_SDK: return "Installs Android SDK 35, ARM-native build tools, and pinned Gradle 8.10.2.";
            case SETUP_PHASE5A: return "Performs the complete fixed Phase 5A phone-local build environment setup.";
            case GIT_STATUS: return "Reads git status in the fixed Will Harness workspace.";
            case GIT_DIFF: return "Reads git diff in the fixed Will Harness workspace.";
            case TESTS: return "Runs unit tests for the fixed Will Harness Android project.";
            case BUILD: return "Builds a debug APK for the fixed Will Harness Android project.";
            default: return "Will Harness developer capability.";
        }
    }
}
