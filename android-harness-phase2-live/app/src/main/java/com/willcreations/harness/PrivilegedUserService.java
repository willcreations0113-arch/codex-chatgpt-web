package com.willcreations.harness;

import android.content.Context;
import android.system.Os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PrivilegedUserService extends IPrivilegedService.Stub {
    private static final int MAX_OUTPUT_CHARS = 20000;

    public PrivilegedUserService() {}
    public PrivilegedUserService(Context context) {}

    @Override
    public String identity() {
        return "pid=" + Os.getpid() + ", uid=" + Os.getuid();
    }

    @Override
    public String listPackages(int limit) {
        return runReadOnly(new String[]{"/system/bin/cmd", "package", "list", "packages"}, clamp(limit, 1, 100));
    }

    @Override
    public String listProcesses(int limit) {
        return runReadOnly(new String[]{"/system/bin/ps", "-A"}, clamp(limit, 1, 100));
    }

    @Override
    public String readLogcat(int lines) {
        int safe = clamp(lines, 1, 200);
        return runReadOnly(new String[]{"/system/bin/logcat", "-d", "-t", String.valueOf(safe)}, safe);
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String runReadOnly(String[] command, int maxLines) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count < maxLines && out.length() < MAX_OUTPUT_CHARS) {
                    out.append(line).append('\n');
                    count++;
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "ERROR: timeout";
            }
            return out.length() == 0 ? "(no output)" : out.toString();
        } catch (Throwable t) {
            return "ERROR: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }
}
