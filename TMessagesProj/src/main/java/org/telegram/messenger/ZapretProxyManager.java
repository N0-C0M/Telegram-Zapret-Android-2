package org.telegram.messenger;

import android.os.Looper;
import android.os.SystemClock;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;

public class ZapretProxyManager implements NotificationCenter.NotificationCenterDelegate {

    private static final String KEY_STATE_SAVED = "zapret_managed_proxy_saved";
    private static final String KEY_PREVIOUS_PROXY_ENABLED = "zapret_prev_proxy_enabled";
    private static final String KEY_PREVIOUS_PROXY_CALLS = "zapret_prev_proxy_calls";
    private static final String KEY_PREVIOUS_PROXY_IP = "zapret_prev_proxy_ip";
    private static final String KEY_PREVIOUS_PROXY_PORT = "zapret_prev_proxy_port";
    private static final String KEY_PREVIOUS_PROXY_USER = "zapret_prev_proxy_user";
    private static final String KEY_PREVIOUS_PROXY_PASS = "zapret_prev_proxy_pass";
    private static final String KEY_PREVIOUS_PROXY_SECRET = "zapret_prev_proxy_secret";
    private static final String KEY_PREVIOUS_PROXY_ROTATION = "zapret_prev_proxy_rotation";
    private static final String KEY_MANAGED_PROXY_CREATED = "zapret_managed_proxy_created";
    private static final String KEY_MANAGED_PROXY_IP = "zapret_managed_proxy_ip";
    private static final String KEY_MANAGED_PROXY_PORT = "zapret_managed_proxy_port";
    private static final String KEY_MANAGED_PROXY_USER = "zapret_managed_proxy_user";
    private static final String KEY_MANAGED_PROXY_PASS = "zapret_managed_proxy_pass";
    private static final long CALL_SCOPE_PREPARE_TIMEOUT_MS = 30_000L;
    private static final long STUCK_PROXY_RELOAD_TIMEOUT_MS = 4_000L;

    private static final ZapretProxyManager INSTANCE = new ZapretProxyManager();

    private boolean initialized;
    private boolean syncing;
    private boolean proxyReloadScheduled;
    private long callScopePreparedUntilRealtime;
    private long appBackgroundEnteredAtRealtime;
    private long appResumedAtRealtime;
    private long connectingStateSinceRealtime;

    private final Runnable clearCallScopePreparationRunnable = () -> {
        if (VoIPService.getSharedInstance() != null) {
            return;
        }
        if (callScopePreparedUntilRealtime != 0 && SystemClock.elapsedRealtime() >= callScopePreparedUntilRealtime) {
            callScopePreparedUntilRealtime = 0;
            syncProxyState();
        }
    };
    private final Runnable reloadStuckProxyRunnable = () -> {
        proxyReloadScheduled = false;
        String reason = getStuckProxyReloadReason(UserConfig.selectedAccount);
        if (reason == null) {
            return;
        }
        forceReloadProxyStack(reason);
    };

    public static void init() {
        INSTANCE.initInternal();
    }

    public static ZapretProxyManager getInstance() {
        return INSTANCE;
    }

    public static void prepareForCallRouting() {
        INSTANCE.prepareForCallRoutingInternal();
    }

    private void initInternal() {
        if (initialized) {
            return;
        }
        initialized = true;
        SharedConfig.loadProxyList();
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.voipServiceCreated);
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.stopAllHeavyOperations);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.startAllHeavyOperations);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zapretSettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didStartedCall);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didEndCall);
        syncProxyState();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didStartedCall || id == NotificationCenter.voipServiceCreated) {
            prepareForCallRoutingInternal();
            return;
        }
        if (id == NotificationCenter.didUpdateConnectionState) {
            if (account == UserConfig.selectedAccount) {
                reevaluateStuckProxyReload(account);
            }
            return;
        }
        if (id == NotificationCenter.stopAllHeavyOperations) {
            if (args.length > 0 && args[0] instanceof Integer && ((Integer) args[0]) == 4096) {
                appBackgroundEnteredAtRealtime = SystemClock.elapsedRealtime();
                appResumedAtRealtime = 0;
                cancelStuckProxyReload();
            }
            return;
        }
        if (id == NotificationCenter.startAllHeavyOperations) {
            if (args.length > 0 && args[0] instanceof Integer && ((Integer) args[0]) == 4096) {
                handleAppResume();
            }
            return;
        }
        if (id == NotificationCenter.didEndCall) {
            clearCallScopePreparation();
        }
        if (id == NotificationCenter.proxySettingsChanged && syncing) {
            return;
        }
        if (id == NotificationCenter.zapretSettingsChanged || id == NotificationCenter.proxySettingsChanged || id == NotificationCenter.didEndCall) {
            syncProxyState();
        }
    }

    public String getRuntimeSummary() {
        if (ZapretConfig.isProxyRoutingEnabled() && ZapretConfig.hasProxyEndpoint()) {
            String endpoint = ZapretConfig.getProxyEndpointLabel();
            if (isManagedProxyActive()) {
                if (ZapretConfig.getScope() == ZapretConfig.SCOPE_CALLS) {
                    return LocaleController.formatString("ZapretDebugBackendProxyCallsFallback", R.string.ZapretDebugBackendProxyCallsFallback, endpoint);
                }
                int coverageString = ZapretConfig.getScope() == ZapretConfig.SCOPE_MESSAGES
                    ? R.string.ZapretProxyCoverageMessages
                    : R.string.ZapretProxyCoverageAll;
                return LocaleController.formatString("ZapretDebugBackendProxyActive", R.string.ZapretDebugBackendProxyActive, endpoint, LocaleController.getString(coverageString));
            }
            return LocaleController.formatString("ZapretDebugBackendProxyConfigured", R.string.ZapretDebugBackendProxyConfigured, endpoint);
        }
        return LocaleController.getString(R.string.ZapretDebugBackendMissingValue);
    }

    public boolean isManagedProxyActive() {
        if (!shouldManageProxy()) {
            return false;
        }
        SharedConfig.loadProxyList();
        SharedPreferences preferences = getPreferences();
        return preferences.getBoolean("proxy_enabled", false)
            && matches(preferences.getString("proxy_ip", ""), preferences.getInt("proxy_port", 1080), preferences.getString("proxy_user", ""), preferences.getString("proxy_pass", ""), preferences.getString("proxy_secret", ""),
                ZapretConfig.getProxyHost(), ZapretConfig.getProxyPort(), ZapretConfig.getProxyUsername(), ZapretConfig.getProxyPassword(), "")
            && matches(SharedConfig.currentProxy, ZapretConfig.getProxyHost(), ZapretConfig.getProxyPort(), ZapretConfig.getProxyUsername(), ZapretConfig.getProxyPassword(), "");
    }

    private void syncProxyState() {
        runOnUiThread(this::syncProxyStateInternal);
    }

    private void syncProxyStateInternal() {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            if (shouldManageProxy()) {
                ZapretWsProxyManager.getInstance().ensureStarted();
                applyManagedProxy();
            } else {
                restorePreviousProxy();
                ZapretWsProxyManager.getInstance().ensureStopped();
            }
        } finally {
            syncing = false;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zapretDebugStateChanged);
            reevaluateStuckProxyReload(UserConfig.selectedAccount);
        }
    }

    private void applyManagedProxy() {
        SharedConfig.loadProxyList();
        SharedPreferences preferences = getPreferences();
        capturePreviousStateIfNeeded(preferences);

        String host = ZapretConfig.getProxyHost();
        int port = ZapretConfig.getProxyPort();
        String username = ZapretConfig.getProxyUsername();
        String password = ZapretConfig.getProxyPassword();
        boolean useProxyForCalls = ZapretConfig.appliesToCalls();
        boolean alreadyApplied = isManagedProxyStateApplied(preferences, host, port, username, password, useProxyForCalls);

        boolean previousManagedCreated = preferences.getBoolean(KEY_MANAGED_PROXY_CREATED, false);
        String previousManagedHost = preferences.getString(KEY_MANAGED_PROXY_IP, "");
        int previousManagedPort = preferences.getInt(KEY_MANAGED_PROXY_PORT, 1080);
        String previousManagedUser = preferences.getString(KEY_MANAGED_PROXY_USER, "");
        String previousManagedPass = preferences.getString(KEY_MANAGED_PROXY_PASS, "");

        SharedConfig.ProxyInfo existingProxy = findProxy(host, port, username, password, "");
        boolean created = existingProxy == null;
        SharedConfig.ProxyInfo proxyInfo = SharedConfig.addProxy(new SharedConfig.ProxyInfo(host, port, username, password, ""));
        SharedConfig.currentProxy = proxyInfo;

        if (SharedConfig.proxyRotationEnabled) {
            SharedConfig.proxyRotationEnabled = false;
            SharedConfig.saveConfig();
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("proxy_ip", host);
        editor.putInt("proxy_port", port);
        editor.putString("proxy_user", username);
        editor.putString("proxy_pass", password);
        editor.putString("proxy_secret", "");
        editor.putBoolean("proxy_enabled", true);
        editor.putBoolean("proxy_enabled_calls", useProxyForCalls);
        editor.putBoolean(KEY_MANAGED_PROXY_CREATED, created);
        editor.putString(KEY_MANAGED_PROXY_IP, host);
        editor.putInt(KEY_MANAGED_PROXY_PORT, port);
        editor.putString(KEY_MANAGED_PROXY_USER, username);
        editor.putString(KEY_MANAGED_PROXY_PASS, password);
        editor.apply();

        ConnectionsManager.setProxySettings(true, host, port, username, password, "");
        pokeConnections();

        if (previousManagedCreated && !matches(previousManagedHost, previousManagedPort, previousManagedUser, previousManagedPass, "", host, port, username, password, "")) {
            removeProxy(previousManagedHost, previousManagedPort, previousManagedUser, previousManagedPass, "");
        }

        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        if (!alreadyApplied) {
            ZapretDiagnosticsController.getInstance().logRuntimeEvent("SOCKS5 route active: " + host + ":" + port + (useProxyForCalls ? " (messages + calls)" : " (messages only)"));
        }
    }

    private void restorePreviousProxy() {
        SharedConfig.loadProxyList();
        SharedPreferences preferences = getPreferences();
        if (!preferences.getBoolean(KEY_STATE_SAVED, false)) {
            return;
        }

        boolean previousProxyEnabled = preferences.getBoolean(KEY_PREVIOUS_PROXY_ENABLED, false);
        boolean previousProxyCalls = preferences.getBoolean(KEY_PREVIOUS_PROXY_CALLS, false);
        String previousHost = preferences.getString(KEY_PREVIOUS_PROXY_IP, "");
        int previousPort = preferences.getInt(KEY_PREVIOUS_PROXY_PORT, 1080);
        String previousUser = preferences.getString(KEY_PREVIOUS_PROXY_USER, "");
        String previousPass = preferences.getString(KEY_PREVIOUS_PROXY_PASS, "");
        String previousSecret = preferences.getString(KEY_PREVIOUS_PROXY_SECRET, "");
        boolean previousRotation = preferences.getBoolean(KEY_PREVIOUS_PROXY_ROTATION, false);
        boolean managedCreated = preferences.getBoolean(KEY_MANAGED_PROXY_CREATED, false);
        String managedHost = preferences.getString(KEY_MANAGED_PROXY_IP, "");
        int managedPort = preferences.getInt(KEY_MANAGED_PROXY_PORT, 1080);
        String managedUser = preferences.getString(KEY_MANAGED_PROXY_USER, "");
        String managedPass = preferences.getString(KEY_MANAGED_PROXY_PASS, "");

        SharedConfig.ProxyInfo previousProxy = null;
        if (!TextUtils.isEmpty(previousHost)) {
            previousProxy = SharedConfig.addProxy(new SharedConfig.ProxyInfo(previousHost, previousPort, previousUser, previousPass, previousSecret));
        }
        SharedConfig.currentProxy = previousProxy;

        if (SharedConfig.proxyRotationEnabled != previousRotation) {
            SharedConfig.proxyRotationEnabled = previousRotation;
            SharedConfig.saveConfig();
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("proxy_ip", previousHost);
        editor.putInt("proxy_port", previousPort);
        editor.putString("proxy_user", previousUser);
        editor.putString("proxy_pass", previousPass);
        editor.putString("proxy_secret", previousSecret);
        editor.putBoolean("proxy_enabled", previousProxyEnabled);
        editor.putBoolean("proxy_enabled_calls", previousProxyCalls);
        clearSavedState(editor);
        editor.apply();

        if (managedCreated) {
            removeProxy(managedHost, managedPort, managedUser, managedPass, "");
        }

        if (previousProxyEnabled && !TextUtils.isEmpty(previousHost)) {
            ConnectionsManager.setProxySettings(true, previousHost, previousPort, previousUser, previousPass, previousSecret);
        } else {
            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
        }
        pokeConnections();

        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        ZapretDiagnosticsController.getInstance().logRuntimeEvent("SOCKS5 route restored to previous proxy state");
    }

    private void capturePreviousStateIfNeeded(SharedPreferences preferences) {
        if (preferences.getBoolean(KEY_STATE_SAVED, false)) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(KEY_STATE_SAVED, true);
        editor.putBoolean(KEY_PREVIOUS_PROXY_ENABLED, preferences.getBoolean("proxy_enabled", false));
        editor.putBoolean(KEY_PREVIOUS_PROXY_CALLS, preferences.getBoolean("proxy_enabled_calls", false));
        editor.putString(KEY_PREVIOUS_PROXY_IP, preferences.getString("proxy_ip", ""));
        editor.putInt(KEY_PREVIOUS_PROXY_PORT, preferences.getInt("proxy_port", 1080));
        editor.putString(KEY_PREVIOUS_PROXY_USER, preferences.getString("proxy_user", ""));
        editor.putString(KEY_PREVIOUS_PROXY_PASS, preferences.getString("proxy_pass", ""));
        editor.putString(KEY_PREVIOUS_PROXY_SECRET, preferences.getString("proxy_secret", ""));
        editor.putBoolean(KEY_PREVIOUS_PROXY_ROTATION, SharedConfig.proxyRotationEnabled);
        editor.apply();
    }

    private void clearSavedState(SharedPreferences.Editor editor) {
        editor.remove(KEY_STATE_SAVED);
        editor.remove(KEY_PREVIOUS_PROXY_ENABLED);
        editor.remove(KEY_PREVIOUS_PROXY_CALLS);
        editor.remove(KEY_PREVIOUS_PROXY_IP);
        editor.remove(KEY_PREVIOUS_PROXY_PORT);
        editor.remove(KEY_PREVIOUS_PROXY_USER);
        editor.remove(KEY_PREVIOUS_PROXY_PASS);
        editor.remove(KEY_PREVIOUS_PROXY_SECRET);
        editor.remove(KEY_PREVIOUS_PROXY_ROTATION);
        editor.remove(KEY_MANAGED_PROXY_CREATED);
        editor.remove(KEY_MANAGED_PROXY_IP);
        editor.remove(KEY_MANAGED_PROXY_PORT);
        editor.remove(KEY_MANAGED_PROXY_USER);
        editor.remove(KEY_MANAGED_PROXY_PASS);
    }

    private void prepareForCallRoutingInternal() {
        if (!shouldPrimeForCall()) {
            return;
        }
        runOnUiThread(() -> {
            callScopePreparedUntilRealtime = SystemClock.elapsedRealtime() + CALL_SCOPE_PREPARE_TIMEOUT_MS;
            AndroidUtilities.cancelRunOnUIThread(clearCallScopePreparationRunnable);
            AndroidUtilities.runOnUIThread(clearCallScopePreparationRunnable, CALL_SCOPE_PREPARE_TIMEOUT_MS);
            syncProxyStateInternal();
        });
    }

    private void clearCallScopePreparation() {
        callScopePreparedUntilRealtime = 0;
        AndroidUtilities.cancelRunOnUIThread(clearCallScopePreparationRunnable);
    }

    private void handleAppResume() {
        runOnUiThread(() -> {
            if (appBackgroundEnteredAtRealtime != 0) {
                appBackgroundEnteredAtRealtime = 0;
            }
            appResumedAtRealtime = SystemClock.elapsedRealtime();
            if (!shouldManageProxy() || ApplicationLoader.mainInterfacePaused) {
                cancelStuckProxyReload();
                return;
            }
            if (ZapretConfig.shouldUseLocalWsProxy()) {
                ZapretWsProxyManager.getInstance().ensureStarted();
            }
            if (!isManagedProxyActive()) {
                syncProxyStateInternal();
                return;
            }
            pokeConnections();
            reevaluateStuckProxyReload(UserConfig.selectedAccount);
        });
    }

    private void reevaluateStuckProxyReload(int account) {
        long reloadDelay = getStuckProxyReloadDelay(account);
        if (reloadDelay < 0) {
            cancelStuckProxyReload();
            return;
        }
        scheduleStuckProxyReload(reloadDelay);
    }

    private void scheduleStuckProxyReload(long delayMs) {
        if (proxyReloadScheduled) {
            AndroidUtilities.cancelRunOnUIThread(reloadStuckProxyRunnable);
        }
        proxyReloadScheduled = true;
        AndroidUtilities.runOnUIThread(reloadStuckProxyRunnable, Math.max(0L, delayMs));
    }

    private void cancelStuckProxyReload() {
        if (!proxyReloadScheduled) {
            return;
        }
        proxyReloadScheduled = false;
        AndroidUtilities.cancelRunOnUIThread(reloadStuckProxyRunnable);
    }

    private boolean isRetryableProxyState(int state) {
        return state == ConnectionsManager.ConnectionStateConnecting
            || state == ConnectionsManager.ConnectionStateConnectingToProxy
            || state == ConnectionsManager.ConnectionStateUpdating;
    }

    private long getStuckProxyReloadDelay(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || account != UserConfig.selectedAccount) {
            return -1L;
        }
        if (!shouldManageProxy() || ApplicationLoader.mainInterfacePaused || ApplicationLoader.mainInterfacePausedStageQueue) {
            connectingStateSinceRealtime = 0;
            return -1L;
        }
        ConnectionsManager manager = ConnectionsManager.getInstance(account);
        if (manager.getPauseTime() != 0) {
            connectingStateSinceRealtime = 0;
            return -1L;
        }
        int state = manager.getConnectionState();
        if (!isRetryableProxyState(state)) {
            connectingStateSinceRealtime = 0;
            return -1L;
        }
        long now = SystemClock.elapsedRealtime();
        if (connectingStateSinceRealtime == 0) {
            connectingStateSinceRealtime = now;
        }
        long reloadAt = connectingStateSinceRealtime + STUCK_PROXY_RELOAD_TIMEOUT_MS;
        if (appResumedAtRealtime != 0) {
            reloadAt = Math.max(reloadAt, appResumedAtRealtime + STUCK_PROXY_RELOAD_TIMEOUT_MS);
        }
        return Math.max(0L, reloadAt - now);
    }

    private String getStuckProxyReloadReason(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || account != UserConfig.selectedAccount) {
            return null;
        }
        if (!shouldManageProxy() || ApplicationLoader.mainInterfacePaused || ApplicationLoader.mainInterfacePausedStageQueue) {
            return null;
        }
        ConnectionsManager manager = ConnectionsManager.getInstance(account);
        if (manager.getPauseTime() != 0) {
            return null;
        }
        int state = manager.getConnectionState();
        if (!isRetryableProxyState(state)) {
            return null;
        }
        return "network:" + ZapretDiagnosticsController.getConnectionStateLabel(state);
    }

    private void forceReloadProxyStack(String reason) {
        runOnUiThread(() -> {
            if (syncing) {
                scheduleStuckProxyReload(STUCK_PROXY_RELOAD_TIMEOUT_MS);
                return;
            }
            syncing = true;
            cancelStuckProxyReload();
            connectingStateSinceRealtime = 0;
            appResumedAtRealtime = 0;
            try {
                if (!shouldManageProxy()) {
                    return;
                }
                ZapretDiagnosticsController.getInstance().logRuntimeEvent("proxy reconnect: " + reason);
                if (ZapretConfig.shouldUseLocalWsProxy()) {
                    ZapretWsProxyManager.getInstance().ensureStopped();
                    ZapretWsProxyManager.getInstance().ensureStarted();
                }
                ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
                applyManagedProxy();
            } finally {
                syncing = false;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zapretDebugStateChanged);
                reevaluateStuckProxyReload(UserConfig.selectedAccount);
            }
        });
    }

    private boolean shouldManageProxy() {
        return ZapretConfig.shouldUseManagedProxyRouting()
            && (ZapretConfig.appliesToMessages() || isCallScopeActive());
    }

    private boolean shouldPrimeForCall() {
        return ZapretConfig.shouldUseManagedProxyRouting()
            && !ZapretConfig.appliesToMessages()
            && ZapretConfig.appliesToCalls();
    }

    private boolean isCallScopeActive() {
        if (!ZapretConfig.appliesToCalls()) {
            return false;
        }
        if (VoIPService.getSharedInstance() != null) {
            return true;
        }
        return callScopePreparedUntilRealtime > SystemClock.elapsedRealtime();
    }

    private void runOnUiThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            AndroidUtilities.runOnUIThread(runnable);
        }
    }

    private boolean isManagedProxyStateApplied(SharedPreferences preferences, String host, int port, String username, String password, boolean useProxyForCalls) {
        return preferences.getBoolean("proxy_enabled", false)
            && preferences.getBoolean("proxy_enabled_calls", false) == useProxyForCalls
            && !SharedConfig.proxyRotationEnabled
            && matches(preferences.getString("proxy_ip", ""), preferences.getInt("proxy_port", 1080), preferences.getString("proxy_user", ""), preferences.getString("proxy_pass", ""), preferences.getString("proxy_secret", ""),
                host, port, username, password, "")
            && matches(SharedConfig.currentProxy, host, port, username, password, "");
    }

    private void pokeConnections() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            ConnectionsManager.getInstance(account).checkConnection();
            ConnectionsManager.getInstance(account).resumeNetworkMaybe();
        }
    }

    private SharedPreferences getPreferences() {
        return MessagesController.getGlobalMainSettings();
    }

    private SharedConfig.ProxyInfo findProxy(String host, int port, String username, String password, String secret) {
        for (int i = 0; i < SharedConfig.proxyList.size(); i++) {
            SharedConfig.ProxyInfo info = SharedConfig.proxyList.get(i);
            if (matches(info, host, port, username, password, secret)) {
                return info;
            }
        }
        return null;
    }

    private void removeProxy(String host, int port, String username, String password, String secret) {
        SharedConfig.ProxyInfo info = findProxy(host, port, username, password, secret);
        if (info == null || info == SharedConfig.currentProxy) {
            return;
        }
        SharedConfig.proxyList.remove(info);
        SharedConfig.saveProxyList();
    }

    private boolean matches(SharedConfig.ProxyInfo info, String host, int port, String username, String password, String secret) {
        return info != null && matches(info.address, info.port, info.username, info.password, info.secret, host, port, username, password, secret);
    }

    private boolean matches(String host1, int port1, String user1, String pass1, String secret1, String host2, int port2, String user2, String pass2, String secret2) {
        return port1 == port2
            && TextUtils.equals(host1, host2)
            && TextUtils.equals(user1, user2)
            && TextUtils.equals(pass1, pass2)
            && TextUtils.equals(secret1, secret2);
    }
}
