package com.willcreations.harness;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class ShizukuBridge {
    public interface Listener {
        void onState(String state);
        void onResult(String title, String result);
    }

    private static final int REQUEST_CODE = 7301;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private IPrivilegedService service;
    private boolean listenersAdded;
    private boolean binding;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> emitState("Shizuku Binder: ready");
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        service = null;
        binding = false;
        emitState("Shizuku Binder: stopped");
    };
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode != REQUEST_CODE) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            emitState("Shizuku permission: granted");
            bindPrivilegedService();
        } else {
            emitState("Shizuku permission: denied");
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            service = IPrivilegedService.Stub.asInterface(binder);
            runCapability("Identity", svc -> svc.identity());
            emitState("Privileged service: connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            service = null;
            emitState("Privileged service: disconnected");
        }
    };

    public ShizukuBridge(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (listenersAdded) return;
        listenersAdded = true;
        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        emitState(Shizuku.pingBinder() ? "Shizuku Binder: ready" : "Shizuku Binder: waiting");
    }

    public void ensureConnected() {
        try {
            if (!Shizuku.pingBinder()) {
                emitState("Shizukuが起動していません");
                return;
            }
            if (Shizuku.isPreV11()) {
                emitState("Shizuku v11未満は非対応");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindPrivilegedService();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                emitState("Shizuku権限が拒否されています。Shizuku側で許可してください");
                return;
            }
            emitState("Shizuku権限を要求中…");
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            emitState("Shizuku error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public void listPackages() {
        runCapability("Packages", svc -> svc.listPackages(40));
    }

    public void listProcesses() {
        runCapability("Processes", svc -> svc.listProcesses(40));
    }

    public void readLogcat() {
        runCapability("Logcat", svc -> svc.readLogcat(80));
    }

    public void stop() {
        if (listenersAdded) {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionListener);
            listenersAdded = false;
        }
        worker.shutdownNow();
    }

    private void bindPrivilegedService() {
        if (service != null) {
            emitState("Privileged service: connected");
            return;
        }
        if (binding) return;
        try {
            binding = true;
            Shizuku.UserServiceArgs args = userServiceArgs();
            Shizuku.bindUserService(args, connection);
            emitState("Privileged service: connecting…");
        } catch (Throwable t) {
            binding = false;
            emitState("Bind error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private Shizuku.UserServiceArgs userServiceArgs() {
        return new Shizuku.UserServiceArgs(new ComponentName(context, PrivilegedUserService.class))
                .processNameSuffix("privileged")
                .tag("will-harness-readonly")
                .version(1)
                .daemon(false)
                .debuggable(true);
    }

    private interface RemoteCall {
        String run(IPrivilegedService service) throws Exception;
    }

    private void runCapability(String title, RemoteCall call) {
        IPrivilegedService current = service;
        if (current == null) {
            emitResult(title, "NOT_CONNECTED: Shizuku接続を先に実行してください");
            return;
        }
        worker.execute(() -> {
            try {
                String result = call.run(current);
                emitResult(title, result);
            } catch (Throwable t) {
                emitResult(title, "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    private void emitState(String state) {
        main.post(() -> listener.onState(state));
    }

    private void emitResult(String title, String result) {
        main.post(() -> listener.onResult(title, result));
    }
}
