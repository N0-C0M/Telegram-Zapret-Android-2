package org.telegram.messenger;

import android.content.Context;

import org.telegram.messenger.tgws.ProxyConfig;
import org.telegram.messenger.tgws.TgWsProxyController;

public class ZapretWsProxyManager implements NotificationCenter.NotificationCenterDelegate {

    private static final ZapretWsProxyManager INSTANCE = new ZapretWsProxyManager();

    private boolean initialized;
    private boolean running;

    public static void init() {
        INSTANCE.initInternal();
    }

    public static ZapretWsProxyManager getInstance() {
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

    public void syncWithConfig() {
        runOnUiThread(() -> {
            if (shouldRunLocalWsProxy()) {
                ensureStarted();
            } else {
                ensureStopped();
            }
        });
    }

    public void ensureStarted() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        ProxyConfig config = new ProxyConfig(
            ZapretConfig.getProxyHost(),
            ZapretConfig.getProxyPort(),
            ProxyConfig.Companion.defaultDcList(),
            true,
            false,
            ZapretConfig.isWsProxyIpv6Enabled(),
            true,
            25
        );
        boolean wasRunning = running;
        TgWsProxyController.INSTANCE.start(context, config);
        running = true;
        if (!wasRunning) {
            ZapretDiagnosticsController.getInstance().logRuntimeEvent("tg ws proxy service started");
        }
    }

    public void ensureStopped() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        boolean wasRunning = running;
        TgWsProxyController.INSTANCE.stop(context);
        running = false;
        if (wasRunning) {
            ZapretDiagnosticsController.getInstance().logRuntimeEvent("tg ws proxy service stopped");
        }
    }

    private boolean shouldRunLocalWsProxy() {
        return ZapretConfig.shouldUseLocalWsProxy();
    }

    private void runOnUiThread(Runnable runnable) {
        AndroidUtilities.runOnUIThread(runnable);
    }
}
