package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;

public final class ZapretConfig {

    public static final int STRATEGY_FAST_FAKE_TLS = 0;
    public static final int STRATEGY_BALANCED_SPLIT = 1;
    public static final int STRATEGY_AGGRESSIVE_DISORDER = 2;
    public static final int STRATEGY_QUIC_FOCUS = 3;
    public static final int STRATEGY_MAX_COMPAT = 4;
    public static final int STRATEGY_TLS_SPLIT_V2 = 5;
    public static final int STRATEGY_MOBILE_FALLBACK = 6;
    public static final int STRATEGY_UDP_HEAVY_RELAY = 7;
    public static final int STRATEGY_STRONG_FAKE_SPLIT = 8;
    public static final int STRATEGY_FINAL_FALLBACK = 9;

    public static final int SCOPE_MESSAGES = 0;
    public static final int SCOPE_CALLS = 1;
    public static final int SCOPE_ALL = 2;

    public static final int DEFAULT_ROTATION_TIMEOUT_INDEX = 1;
    public static final int[] ROTATION_TIMEOUTS = {6, 10, 15, 20, 30};
    public static final int MAX_CUSTOM_CONFIG_LENGTH = 32 * 1024;

    private static final String KEY_ENABLED = "zapret_enabled";
    private static final String KEY_STRATEGY = "zapret_strategy";
    private static final String KEY_SCOPE = "zapret_scope";
    private static final String KEY_AUTO_ROTATION = "zapret_auto_rotation";
    private static final String KEY_ROTATION_TIMEOUT = "zapret_rotation_timeout";
    private static final String KEY_USE_CUSTOM = "zapret_use_custom";
    private static final String KEY_CUSTOM_NAME = "zapret_custom_name";
    private static final String KEY_CUSTOM_CONFIG = "zapret_custom_config";
    private static final String KEY_PROXY_ROUTING = "zapret_proxy_routing";
    private static final String KEY_PROXY_HOST = "zapret_proxy_host";
    private static final String KEY_PROXY_PORT = "zapret_proxy_port";
    private static final String KEY_PROXY_USERNAME = "zapret_proxy_username";
    private static final String KEY_PROXY_PASSWORD = "zapret_proxy_password";
    private static final String KEY_WS_PROXY_ENABLED = "zapret_ws_proxy_enabled";
    private static final String KEY_WS_PROXY_IPV6_ENABLED = "zapret_ws_proxy_ipv6_enabled";
    private static final String KEY_WS_PROXY_NOTIFICATION_ENABLED = "zapret_ws_proxy_notification_enabled";
    private static final String KEY_LOCAL_VPN_ENABLED = "zapret_local_vpn_enabled";
    private static final String KEY_CALL_COMPATIBILITY_MODE = "zapret_call_compat_mode";

    private static final String[] BUILTIN_CONFIGS = {
        "# PC general.bat adapted for Telegram-only native runtime\n" +
        "--filter-tcp=1-65535 --dpi-desync=multisplit --dpi-desync-split-pos=1 --dpi-desync-cutoff=n3 --dpi-desync-repeats=6\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-cutoff=n2\n",
        "# PC SIMPLE FAKE adapted for Telegram-only native runtime\n" +
        "--filter-tcp=1-65535 --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-cutoff=n5\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-repeats=12 --dpi-desync-cutoff=n3\n",
        "# PC FAKE TLS AUTO adapted for Telegram-only native runtime\n" +
        "--filter-tcp=1-65535 --dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,midsld --dpi-desync-repeats=11 --dpi-desync-cutoff=n4\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-repeats=10 --dpi-desync-cutoff=n2\n",
        "# PC ALT fakedsplit adapted for Telegram-only native runtime\n" +
        "--filter-tcp=1-65535 --dpi-desync=fake,fakedsplit --dpi-desync-repeats=6 --dpi-desync-cutoff=n4\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-repeats=12 --dpi-desync-cutoff=n3\n",
        "# PC ALT4 badseq multisplit adapted for Telegram-only native runtime\n" +
        "--filter-tcp=1-65535 --dpi-desync=fake,multisplit --dpi-desync-split-pos=1,midsld --dpi-desync-repeats=6 --dpi-desync-cutoff=n3\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-repeats=10 --dpi-desync-cutoff=n2\n",
        "# PC ALT10 fake TLS adapted for Telegram-only native runtime\n" +
        "--filter-tcp=1-65535 --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-cutoff=n3\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-repeats=12 --dpi-desync-cutoff=n2\n",
        "# Telegram relay split\n" +
        "--filter-tcp=1-65535 --dpi-desync=multisplit --dpi-desync-split-pos=1,sniext+1,midsld --dpi-desync-repeats=8 --dpi-desync-cutoff=n5\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-repeats=8 --dpi-desync-cutoff=n3\n",
        "# Telegram group voice\n" +
        "--filter-tcp=1-65535 --dpi-desync=fake,multidisorder --dpi-desync-split-pos=1,24,midsld --dpi-desync-repeats=10 --dpi-desync-cutoff=n5\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-any-protocol=1 --dpi-desync-repeats=14 --dpi-desync-cutoff=n2\n",
        "# Mobile safe fallback\n" +
        "--filter-tcp=1-65535 --dpi-desync=split2 --dpi-desync-split-pos=8 --dpi-desync-repeats=4 --dpi-desync-cutoff=n3\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-repeats=6 --dpi-desync-cutoff=n2\n",
        "# Final aggressive fallback\n" +
        "--filter-tcp=1-65535 --dpi-desync=fake,hostfakesplit,multidisorder --dpi-desync-split-pos=1,sniext+1,midsld --dpi-desync-repeats=12 --dpi-desync-cutoff=n5\n" +
        "--filter-udp=1-65535 --dpi-desync=fake --dpi-desync-any-protocol=1 --dpi-desync-repeats=14 --dpi-desync-cutoff=n2\n"
    };

    private static final int[] STRATEGY_TITLE_RES = {
        R.string.ZapretStrategyFastFakeTls,
        R.string.ZapretStrategyBalancedSplit,
        R.string.ZapretStrategyAggressiveDisorder,
        R.string.ZapretStrategyQuicFocus,
        R.string.ZapretStrategyMaximumCompatibility,
        R.string.ZapretStrategyTlsSplitV2,
        R.string.ZapretStrategyMobileFallback,
        R.string.ZapretStrategyUdpHeavyRelay,
        R.string.ZapretStrategyStrongFakeSplit,
        R.string.ZapretStrategyFinalFallback
    };

    private static final int[] STRATEGY_SUMMARY_RES = {
        R.string.ZapretStrategyFastFakeTlsInfo,
        R.string.ZapretStrategyBalancedSplitInfo,
        R.string.ZapretStrategyAggressiveDisorderInfo,
        R.string.ZapretStrategyQuicFocusInfo,
        R.string.ZapretStrategyMaximumCompatibilityInfo,
        R.string.ZapretStrategyTlsSplitV2Info,
        R.string.ZapretStrategyMobileFallbackInfo,
        R.string.ZapretStrategyUdpHeavyRelayInfo,
        R.string.ZapretStrategyStrongFakeSplitInfo,
        R.string.ZapretStrategyFinalFallbackInfo
    };

    private static final int[] SCOPE_TITLE_RES = {
        R.string.ZapretScopeMessages,
        R.string.ZapretScopeCalls,
        R.string.ZapretScopeAll
    };

    public static final int STRATEGY_COUNT = BUILTIN_CONFIGS.length;

    private ZapretConfig() {
    }

    private static SharedPreferences getPreferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isEnabled() {
        return getPreferences().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_ENABLED, enabled).apply();
        notifyConfigChanged();
    }

    public static int getSelectedStrategy() {
        int strategy = getPreferences().getInt(KEY_STRATEGY, STRATEGY_FAST_FAKE_TLS);
        return normalizeStrategy(strategy);
    }

    public static void setSelectedStrategy(int strategy) {
        getPreferences().edit().putInt(KEY_STRATEGY, normalizeStrategy(strategy)).apply();
        notifyConfigChanged();
    }

    public static void selectNextStrategy() {
        setSelectedStrategy((getSelectedStrategy() + 1) % STRATEGY_COUNT);
    }

    public static int getScope() {
        return SCOPE_ALL;
    }

    public static void setScope(int scope) {
        getPreferences().edit().putInt(KEY_SCOPE, SCOPE_ALL).apply();
        notifyConfigChanged();
    }

    public static boolean appliesToMessages() {
        int scope = getScope();
        return scope == SCOPE_MESSAGES || scope == SCOPE_ALL;
    }

    public static boolean appliesToCalls() {
        int scope = getScope();
        return scope == SCOPE_CALLS || scope == SCOPE_ALL;
    }

    public static boolean isAutoRotationEnabled() {
        return false;
    }

    public static void setAutoRotationEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_AUTO_ROTATION, false).apply();
        notifyConfigChanged();
    }

    public static int getRotationTimeoutIndex() {
        return normalizeRotationTimeoutIndex(getPreferences().getInt(KEY_ROTATION_TIMEOUT, DEFAULT_ROTATION_TIMEOUT_INDEX));
    }

    public static void setRotationTimeoutIndex(int index) {
        getPreferences().edit().putInt(KEY_ROTATION_TIMEOUT, normalizeRotationTimeoutIndex(index)).apply();
        notifyConfigChanged();
    }

    public static int getRotationTimeoutSeconds() {
        return ROTATION_TIMEOUTS[getRotationTimeoutIndex()];
    }

    public static boolean isUsingCustomConfig() {
        return false;
    }

    public static void setUseCustomConfig(boolean useCustomConfig) {
        getPreferences().edit().putBoolean(KEY_USE_CUSTOM, false).apply();
        notifyConfigChanged();
    }

    public static boolean hasCustomConfig() {
        return !TextUtils.isEmpty(getCustomConfig());
    }

    public static boolean isProxyRoutingEnabled() {
        return true;
    }

    public static void setProxyRoutingEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_PROXY_ROUTING, true).apply();
        notifyConfigChanged();
    }

    public static boolean isLocalVpnEnabled() {
        return false;
    }

    public static void setLocalVpnEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_LOCAL_VPN_ENABLED, false).apply();
        notifyConfigChanged();
    }

    public static String getProxyHost() {
        String host = normalizeProxyHost(getPreferences().getString(KEY_PROXY_HOST, "127.0.0.1"));
        return TextUtils.isEmpty(host) ? "127.0.0.1" : host;
    }

    public static void setProxyHost(String host) {
        getPreferences().edit().putString(KEY_PROXY_HOST, normalizeProxyHost(host)).apply();
        notifyConfigChanged();
    }

    public static int getProxyPort() {
        return normalizeProxyPort(getPreferences().getInt(KEY_PROXY_PORT, 1080));
    }

    public static void setProxyPort(int port) {
        getPreferences().edit().putInt(KEY_PROXY_PORT, normalizeProxyPort(port)).apply();
        notifyConfigChanged();
    }

    public static String getProxyUsername() {
        return getPreferences().getString(KEY_PROXY_USERNAME, "");
    }

    public static void setProxyUsername(String username) {
        getPreferences().edit().putString(KEY_PROXY_USERNAME, normalizeProxyCredential(username)).apply();
        notifyConfigChanged();
    }

    public static String getProxyPassword() {
        return getPreferences().getString(KEY_PROXY_PASSWORD, "");
    }

    public static void setProxyPassword(String password) {
        getPreferences().edit().putString(KEY_PROXY_PASSWORD, normalizeProxyCredential(password)).apply();
        notifyConfigChanged();
    }

    public static boolean isWsProxyEnabled() {
        SharedPreferences preferences = getPreferences();
        if (preferences.contains(KEY_WS_PROXY_ENABLED)) {
            return preferences.getBoolean(KEY_WS_PROXY_ENABLED, true);
        }
        return isEnabled();
    }

    public static void setWsProxyEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_WS_PROXY_ENABLED, enabled).apply();
        notifyConfigChanged();
    }

    public static boolean isWsProxyIpv6Enabled() {
        return getPreferences().getBoolean(KEY_WS_PROXY_IPV6_ENABLED, true);
    }

    public static void setWsProxyIpv6Enabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_WS_PROXY_IPV6_ENABLED, enabled).apply();
        notifyConfigChanged();
    }

    public static boolean isWsProxyNotificationEnabled() {
        return getPreferences().getBoolean(KEY_WS_PROXY_NOTIFICATION_ENABLED, false);
    }

    public static void setWsProxyNotificationEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_WS_PROXY_NOTIFICATION_ENABLED, enabled).apply();
        notifyConfigChanged();
    }

    public static boolean hasProxyEndpoint() {
        return !TextUtils.isEmpty(getProxyHost()) && getProxyPort() > 0;
    }

    public static String getProxyEndpointLabel() {
        return getProxyHost() + ":" + getProxyPort();
    }

    public static boolean isLocalProxyEndpoint() {
        String host = getProxyHost();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    public static boolean shouldUseLocalWsProxy() {
        return isProxyRoutingEnabled()
            && hasProxyEndpoint()
            && isLocalProxyEndpoint()
            && isWsProxyEnabled();
    }

    public static boolean shouldUseStandaloneWsProxyRouting() {
        return !isEnabled() && shouldUseLocalWsProxy();
    }

    public static boolean shouldUseManagedProxyRouting() {
        if (shouldUseLocalWsProxy()) {
            return true;
        }
        return isEnabled()
            && isProxyRoutingEnabled()
            && hasProxyEndpoint()
            && (!isLocalProxyEndpoint() || isWsProxyEnabled());
    }

    public static String getProxyUsernameSummary() {
        String username = getProxyUsername();
        return TextUtils.isEmpty(username) ? LocaleController.getString(R.string.ZapretProxyNotSet) : username;
    }

    public static String getProxyPasswordSummary() {
        return TextUtils.isEmpty(getProxyPassword()) ? LocaleController.getString(R.string.ZapretProxyNotSet) : "\u2022\u2022\u2022\u2022\u2022";
    }

    public static String getCustomConfigName() {
        return getPreferences().getString(KEY_CUSTOM_NAME, "");
    }

    public static String getCustomConfig() {
        return normalizeConfig(getPreferences().getString(KEY_CUSTOM_CONFIG, ""));
    }

    public static String normalizeConfig(String config) {
        if (config == null) {
            return "";
        }
        String normalized = config.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1);
        }
        return normalized.trim();
    }

    public static boolean isValidCustomConfig(String config) {
        String normalized = normalizeConfig(config);
        return !TextUtils.isEmpty(normalized) && normalized.length() <= MAX_CUSTOM_CONFIG_LENGTH;
    }

    public static boolean setCustomConfig(String name, String config) {
        String normalizedConfig = normalizeConfig(config);
        if (!isValidCustomConfig(normalizedConfig)) {
            return false;
        }
        String normalizedName = FileLoader.fixFileName(name);
        if (TextUtils.isEmpty(normalizedName)) {
            normalizedName = "custom-zapret.conf";
        }
        getPreferences().edit()
            .putString(KEY_CUSTOM_NAME, normalizedName)
            .putString(KEY_CUSTOM_CONFIG, normalizedConfig)
            .putBoolean(KEY_USE_CUSTOM, true)
            .apply();
        notifyConfigChanged();
        return true;
    }

    public static void clearCustomConfig() {
        getPreferences().edit()
            .remove(KEY_CUSTOM_NAME)
            .remove(KEY_CUSTOM_CONFIG)
            .putBoolean(KEY_USE_CUSTOM, false)
            .apply();
        notifyConfigChanged();
    }

    public static String getBuiltInConfig(int strategy) {
        return BUILTIN_CONFIGS[normalizeStrategy(strategy)];
    }

    public static String getActiveConfig() {
        if (isUsingCustomConfig()) {
            return getCustomConfig();
        }
        return getBuiltInConfig(getSelectedStrategy());
    }

    public static String getStrategyTitle(int strategy) {
        return LocaleController.getString(STRATEGY_TITLE_RES[normalizeStrategy(strategy)]);
    }

    public static String getSelectedStrategyTitle() {
        return getStrategyTitle(getSelectedStrategy());
    }

    public static String[] getStrategyTitles() {
        String[] titles = new String[STRATEGY_COUNT];
        for (int i = 0; i < STRATEGY_COUNT; i++) {
            titles[i] = getStrategyTitle(i);
        }
        return titles;
    }

    public static String getSelectedStrategySummary() {
        return LocaleController.getString(STRATEGY_SUMMARY_RES[getSelectedStrategy()]);
    }

    public static String getCurrentSourceTitle() {
        return LocaleController.getString(isUsingCustomConfig() ? R.string.ZapretSourceCustom : R.string.ZapretSourceBuiltIn);
    }

    public static String getScopeTitle() {
        return LocaleController.getString(SCOPE_TITLE_RES[getScope()]);
    }

    public static String[] getScopeTitles() {
        String[] titles = new String[SCOPE_TITLE_RES.length];
        for (int i = 0; i < SCOPE_TITLE_RES.length; i++) {
            titles[i] = LocaleController.getString(SCOPE_TITLE_RES[i]);
        }
        return titles;
    }

    public static String[] getRotationTimeoutTitles() {
        String[] titles = new String[ROTATION_TIMEOUTS.length];
        for (int i = 0; i < ROTATION_TIMEOUTS.length; i++) {
            titles[i] = LocaleController.formatString("ZapretTimeoutValue", R.string.ZapretTimeoutValue, ROTATION_TIMEOUTS[i]);
        }
        return titles;
    }

    public static String getRotationTimeoutTitle() {
        return LocaleController.formatString("ZapretTimeoutValue", R.string.ZapretTimeoutValue, getRotationTimeoutSeconds());
    }

    public static String getActiveConfigDisplayName() {
        if (isUsingCustomConfig()) {
            String customName = getCustomConfigName();
            if (!TextUtils.isEmpty(customName)) {
                return customName;
            }
            return LocaleController.getString(R.string.ZapretSourceCustom);
        }
        return getSelectedStrategyTitle();
    }

    public static String getSettingsSummary() {
        if (isWsProxyEnabled()) {
            return LocaleController.getString(R.string.ZapretWsProxyStandaloneShort) + " / " + getProxyEndpointLabel();
        }
        return LocaleController.getString(R.string.ZapretDisabled);
    }

    public static String getInfoText() {
        StringBuilder builder = new StringBuilder();
        builder.append(getSelectedStrategySummary());
        builder.append("\n\n");
        builder.append(LocaleController.getString(R.string.ZapretTelegramOnlyStrategyPackInfo));
        return builder.toString();
    }

    public static boolean shouldAutoRotateBuiltInProfilesForMessages() {
        return shouldAutoRotateBuiltInProfiles() && appliesToMessages();
    }

    public static boolean shouldAutoRotateBuiltInProfilesForCalls() {
        return shouldAutoRotateBuiltInProfiles() && appliesToCalls();
    }

    public static boolean shouldAutoRotateBuiltInProfiles() {
        return isEnabled() && !isUsingCustomConfig() && isAutoRotationEnabled();
    }

    public static boolean shouldHardenCallsOverProxy() {
        return isEnabled() && isCallCompatibilityModeEnabled();
    }

    public static boolean shouldUseLocalVpn() {
        return false;
    }

    public static boolean isCallCompatibilityModeEnabled() {
        return getPreferences().getBoolean(KEY_CALL_COMPATIBILITY_MODE, true);
    }

    public static void setCallCompatibilityModeEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_CALL_COMPATIBILITY_MODE, enabled).apply();
        notifyConfigChanged();
    }

    public static void syncNativeConfig() {
        sanitizePreferencesForNativeOnly();
        try {
            ConnectionsManager.native_setZapretConfig(isEnabled(), appliesToMessages(), appliesToCalls(), getActiveConfig());
        } catch (UnsatisfiedLinkError ignore) {
        }
    }

    private static int normalizeStrategy(int strategy) {
        if (strategy < 0 || strategy >= STRATEGY_COUNT) {
            return STRATEGY_FAST_FAKE_TLS;
        }
        return strategy;
    }

    private static int normalizeScope(int scope) {
        if (scope < 0 || scope >= SCOPE_TITLE_RES.length) {
            return SCOPE_ALL;
        }
        return scope;
    }

    private static int normalizeRotationTimeoutIndex(int index) {
        if (index < 0 || index >= ROTATION_TIMEOUTS.length) {
            return DEFAULT_ROTATION_TIMEOUT_INDEX;
        }
        return index;
    }

    private static String normalizeProxyHost(String host) {
        return host == null ? "" : host.trim();
    }

    private static int normalizeProxyPort(int port) {
        if (port <= 0 || port > 65535) {
            return 1080;
        }
        return port;
    }

    private static String normalizeProxyCredential(String value) {
        return value == null ? "" : value.trim();
    }

    private static void sanitizePreferencesForNativeOnly() {
        SharedPreferences preferences = getPreferences();
        SharedPreferences.Editor editor = null;
        if (preferences.getInt(KEY_SCOPE, SCOPE_ALL) != SCOPE_ALL) {
            editor = editor != null ? editor : preferences.edit();
            editor.putInt(KEY_SCOPE, SCOPE_ALL);
        }
        if (preferences.getBoolean(KEY_AUTO_ROTATION, false)) {
            editor = editor != null ? editor : preferences.edit();
            editor.putBoolean(KEY_AUTO_ROTATION, false);
        }
        if (preferences.getBoolean(KEY_USE_CUSTOM, false)) {
            editor = editor != null ? editor : preferences.edit();
            editor.putBoolean(KEY_USE_CUSTOM, false);
        }
        if (preferences.getBoolean(KEY_LOCAL_VPN_ENABLED, false)) {
            editor = editor != null ? editor : preferences.edit();
            editor.putBoolean(KEY_LOCAL_VPN_ENABLED, false);
        }
        if (editor != null) {
            editor.apply();
        }
    }

    private static void notifyConfigChanged() {
        sanitizePreferencesForNativeOnly();
        syncNativeConfig();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zapretSettingsChanged));
    }
}
