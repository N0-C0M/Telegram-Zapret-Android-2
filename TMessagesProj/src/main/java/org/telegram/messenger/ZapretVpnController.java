package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Looper;
import android.text.TextUtils;

public class ZapretVpnController implements NotificationCenter.NotificationCenterDelegate {

    private static final int STATE_STOPPED = 0;
    private static final int STATE_PERMISSION_REQUIRED = 1;
    private static final int STATE_STARTING = 2;
    private static final int STATE_RUNNING = 3;
    private static final int STATE_FAILED = 4;

    private static final ZapretVpnController INSTANCE = new ZapretVpnController();

    private boolean initialized;
    private int state = STATE_STOPPED;
    private String lastError;

    public static void init() {
        INSTANCE.initInternal();
    }

    public static ZapretVpnController getInstance() {
        return INSTANCE;
    }

    private void initInternal() {
        if (initialized) {
            return;
        }
        initialized = true;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zapretSettingsChanged);
        syncWithConfig();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.zapretSettingsChanged) {
            syncWithConfig();
        }
    }

    public boolean hasVpnPermission() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        try {
            return VpnService.prepare(context) == null;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public boolean isRunning() {
        return state == STATE_RUNNING;
    }

    public String getStatusLabel() {
        switch (state) {
            case STATE_PERMISSION_REQUIRED:
                return LocaleController.getString(R.string.ZapretLocalVpnStatePermission);
            case STATE_STARTING:
                return LocaleController.getString(R.string.ZapretLocalVpnStateStarting);
            case STATE_RUNNING:
                return LocaleController.getString(R.string.ZapretLocalVpnStateRunning);
            case STATE_FAILED:
                return LocaleController.getString(R.string.ZapretLocalVpnStateFailed);
            case STATE_STOPPED:
            default:
                return LocaleController.getString(R.string.ZapretLocalVpnStateOff);
        }
    }

    public String getRuntimeSummary() {
        if (!ZapretConfig.isLocalVpnEnabled()) {
            return LocaleController.getString(R.string.ZapretDebugBackendLocalVpnConfigured);
        }
        switch (state) {
            case STATE_PERMISSION_REQUIRED:
                return LocaleController.getString(R.string.ZapretDebugBackendLocalVpnPermission);
            case STATE_STARTING:
                return LocaleController.getString(R.string.ZapretDebugBackendLocalVpnStarting);
            case STATE_RUNNING:
                return LocaleController.getString(R.string.ZapretDebugBackendLocalVpnActive);
            case STATE_FAILED:
                return LocaleController.formatString("ZapretDebugBackendLocalVpnFailed", R.string.ZapretDebugBackendLocalVpnFailed, TextUtils.isEmpty(lastError) ? LocaleController.getString(R.string.ZapretLocalVpnStateFailed) : lastError);
            case STATE_STOPPED:
            default:
                return LocaleController.getString(R.string.ZapretDebugBackendLocalVpnConfigured);
        }
    }

    public void start() {
        runOnUiThread(this::startInternal);
    }

    public void onServiceStarting() {
        runOnUiThread(() -> updateState(STATE_STARTING, null));
    }

    public void onTunnelEstablished() {
        runOnUiThread(() -> updateState(STATE_RUNNING, null));
    }

    public void onServiceStopped(String error) {
        runOnUiThread(() -> {
            if (!ZapretConfig.shouldUseLocalVpn()) {
                updateState(STATE_STOPPED, null);
            } else if (!hasVpnPermission()) {
                updateState(STATE_PERMISSION_REQUIRED, null);
            } else if (!TextUtils.isEmpty(error)) {
                updateState(STATE_FAILED, error);
            } else {
                updateState(STATE_STOPPED, null);
            }
        });
    }

    private void syncWithConfig() {
        runOnUiThread(() -> {
            if (!ZapretConfig.shouldUseLocalVpn()) {
                stopServiceInternal();
                updateState(STATE_STOPPED, null);
            } else if (!hasVpnPermission()) {
                stopServiceInternal();
                updateState(STATE_PERMISSION_REQUIRED, null);
            } else {
                startInternal();
            }
        });
    }

    private void startInternal() {
        if (!ZapretConfig.shouldUseLocalVpn()) {
            stopServiceInternal();
            updateState(STATE_STOPPED, null);
            return;
        }
        if (!hasVpnPermission()) {
            stopServiceInternal();
            updateState(STATE_PERMISSION_REQUIRED, null);
            return;
        }
        if (state != STATE_RUNNING) {
            updateState(STATE_STARTING, null);
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            updateState(STATE_FAILED, "context unavailable");
            return;
        }
        Intent intent = new Intent(context, ZapretVpnService.class);
        intent.setAction(ZapretVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            updateState(STATE_FAILED, e.getMessage());
        }
    }

    private void stopServiceInternal() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, ZapretVpnService.class));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void updateState(int newState, String error) {
        if (state == newState && TextUtils.equals(lastError, error)) {
            return;
        }
        state = newState;
        lastError = error;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zapretVpnStateChanged);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zapretDebugStateChanged);
    }

    private void runOnUiThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            AndroidUtilities.runOnUIThread(runnable);
        }
    }
}
