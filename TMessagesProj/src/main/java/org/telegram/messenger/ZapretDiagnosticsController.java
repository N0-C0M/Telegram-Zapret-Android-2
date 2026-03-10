package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;

import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

public class ZapretDiagnosticsController implements NotificationCenter.NotificationCenterDelegate, VoIPService.StateListener {

    public static final int TEST_CONNECTION = 0;
    public static final int TEST_MESSAGE = 1;
    public static final int TEST_IMAGE = 2;
    public static final int TEST_CALL = 3;

    private static final int STATUS_IDLE = 0;
    private static final int STATUS_RUNNING = 1;
    private static final int STATUS_SUCCESS = 2;
    private static final int STATUS_FAILED = 3;
    private static final int MAX_LOG_LINES = 120;

    private static final ZapretDiagnosticsController INSTANCE = new ZapretDiagnosticsController();

    private final ArrayList<String> logLines = new ArrayList<>();
    private final TestState[] tests = {
        new TestState(),
        new TestState(),
        new TestState(),
        new TestState()
    };

    private boolean initialized;
    private boolean rotationScheduled;
    private long rotationDeadlineRealtime;
    private String rotationReason;
    private String lastRotationSummary;
    private int selectedAccountState = ConnectionsManager.ConnectionStateWaitingForNetwork;
    private int currentVoipState;
    private VoIPService attachedVoipService;
    private int callTestToken;

    public static void init() {
        INSTANCE.initInternal();
    }

    public static ZapretDiagnosticsController getInstance() {
        return INSTANCE;
    }

    public static String getConnectionStateLabel(int state) {
        switch (state) {
            case ConnectionsManager.ConnectionStateConnecting:
                return "connecting";
            case ConnectionsManager.ConnectionStateWaitingForNetwork:
                return "waiting for network";
            case ConnectionsManager.ConnectionStateConnected:
                return "connected";
            case ConnectionsManager.ConnectionStateConnectingToProxy:
                return "connecting to proxy";
            case ConnectionsManager.ConnectionStateUpdating:
                return "updating";
            default:
                return "unknown";
        }
    }

    public static String getCallStateLabel(int state) {
        switch (state) {
            case VoIPService.STATE_REQUESTING:
                return "requesting";
            case VoIPService.STATE_CREATING:
                return "creating";
            case VoIPService.STATE_WAITING:
                return "waiting";
            case VoIPService.STATE_RINGING:
                return "ringing";
            case VoIPService.STATE_EXCHANGING_KEYS:
                return "exchanging keys";
            case VoIPService.STATE_WAIT_INIT:
                return "wait init";
            case VoIPService.STATE_WAIT_INIT_ACK:
                return "wait init ack";
            case VoIPService.STATE_ESTABLISHED:
                return "established";
            case VoIPService.STATE_RECONNECTING:
                return "reconnecting";
            case VoIPService.STATE_BUSY:
                return "busy";
            case VoIPService.STATE_FAILED:
                return "failed";
            case VoIPService.STATE_ENDED:
                return "ended";
            case VoIPService.STATE_HANGING_UP:
                return "hanging up";
            case VoIPService.STATE_WAITING_INCOMING:
                return "incoming";
            default:
                return "idle";
        }
    }

    private void initInternal() {
        if (initialized) {
            return;
        }
        initialized = true;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.voipServiceCreated);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zapretSettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zapretVpnStateChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didStartedCall);
        updateCurrentStates();
        appendLog("diagnostics ready");
    }

    public String getRuntimeSummary() {
        if (!ZapretConfig.isEnabled()) {
            return LocaleController.getString(R.string.ZapretDebugBackendNativeDisabled);
        }
        return LocaleController.formatString("ZapretDebugBackendNativeActive", R.string.ZapretDebugBackendNativeActive, ZapretConfig.getActiveConfigDisplayName());
    }

    public String getSelectedConnectionStateSummary() {
        updateSelectedAccountState();
        return getConnectionStateLabel(selectedAccountState);
    }

    public String getCurrentCallStateSummary() {
        ensureVoipListener();
        return getCallStateLabel(currentVoipState);
    }

    public String getRotationSummary() {
        if (!ZapretConfig.shouldAutoRotateBuiltInProfiles()) {
            return LocaleController.getString(R.string.ZapretDebugRotationDisabled);
        }
        if (rotationScheduled) {
            long leftMs = Math.max(0, rotationDeadlineRealtime - SystemClock.elapsedRealtime());
            int seconds = (int) Math.max(1, (leftMs + 999L) / 1000L);
            if (TextUtils.isEmpty(rotationReason)) {
                return LocaleController.formatString("ZapretDebugRotationScheduled", R.string.ZapretDebugRotationScheduled, seconds);
            }
            return LocaleController.formatString("ZapretDebugRotationScheduledWithReason", R.string.ZapretDebugRotationScheduledWithReason, seconds, rotationReason);
        }
        if (!TextUtils.isEmpty(lastRotationSummary)) {
            return lastRotationSummary;
        }
        return LocaleController.getString(R.string.ZapretDebugRotationIdle);
    }

    public String getTestSummary(int testType) {
        TestState state = tests[testType];
        String status;
        switch (state.status) {
            case STATUS_RUNNING:
                status = LocaleController.getString(R.string.ZapretDebugStatusRunning);
                break;
            case STATUS_SUCCESS:
                status = LocaleController.getString(R.string.ZapretDebugStatusSuccess);
                break;
            case STATUS_FAILED:
                status = LocaleController.getString(R.string.ZapretDebugStatusFailed);
                break;
            case STATUS_IDLE:
            default:
                status = LocaleController.getString(R.string.ZapretDebugStatusIdle);
                break;
        }
        if (TextUtils.isEmpty(state.detail)) {
            return status;
        }
        return status + " - " + state.detail;
    }

    public String getOverviewText() {
        StringBuilder builder = new StringBuilder();
        builder.append(LocaleController.getString(R.string.ZapretDebugRuntimeNotice));
        builder.append("\n\n");
        String logs = getRecentLogPreview();
        builder.append(logs);
        return builder.toString();
    }

    public String getFullLogText() {
        if (logLines.isEmpty()) {
            return LocaleController.getString(R.string.ZapretDebugLogEmpty);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < logLines.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(logLines.get(i));
        }
        return builder.toString();
    }

    public void clearLog() {
        AndroidUtilities.runOnUIThread(() -> {
            logLines.clear();
            lastRotationSummary = null;
            postUpdate();
        });
    }

    public void logRuntimeEvent(String message) {
        AndroidUtilities.runOnUIThread(() -> appendLog(message));
    }

    public void onRotationScheduled(String reason, int timeoutSeconds) {
        AndroidUtilities.runOnUIThread(() -> {
            rotationScheduled = true;
            rotationReason = reason;
            rotationDeadlineRealtime = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L;
            appendLog("rotation scheduled in " + timeoutSeconds + " sec (" + reason + ")");
        });
    }

    public void onRotationCancelled(String reason) {
        AndroidUtilities.runOnUIThread(() -> {
            boolean hadRotation = rotationScheduled;
            rotationScheduled = false;
            rotationReason = null;
            rotationDeadlineRealtime = 0;
            if (hadRotation && !TextUtils.isEmpty(reason)) {
                appendLog("rotation cancelled (" + reason + ")");
            } else {
                postUpdate();
            }
        });
    }

    public void onRotationPerformed(int fromStrategy, int toStrategy, String reason) {
        AndroidUtilities.runOnUIThread(() -> {
            rotationScheduled = false;
            rotationReason = null;
            rotationDeadlineRealtime = 0;
            lastRotationSummary = ZapretConfig.getStrategyTitle(fromStrategy) + " -> " + ZapretConfig.getStrategyTitle(toStrategy);
            if (!TextUtils.isEmpty(reason)) {
                lastRotationSummary += " (" + reason + ")";
            }
            appendLog("strategy switched: " + lastRotationSummary);
        });
    }

    public void runConnectionTest(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            setTestState(TEST_CONNECTION, STATUS_RUNNING, "help.getConfig", true);
            appendLog("connection test started, strategy " + ZapretConfig.getActiveConfigDisplayName());
            TLRPC.TL_help_getConfig req = new TLRPC.TL_help_getConfig();
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    setTestState(TEST_CONNECTION, STATUS_FAILED, error.text, true);
                    return;
                }
                int dcs = response instanceof TLRPC.TL_config ? ((TLRPC.TL_config) response).dc_options.size() : 0;
                setTestState(TEST_CONNECTION, STATUS_SUCCESS, "dc options: " + dcs, true);
            }));
        });
    }

    public void runMessageTest(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            long selfId = UserConfig.getInstance(account).getClientUserId();
            if (selfId == 0) {
                setTestState(TEST_MESSAGE, STATUS_FAILED, "self dialog unavailable", true);
                return;
            }
            TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(selfId);
            if (peer == null) {
                setTestState(TEST_MESSAGE, STATUS_FAILED, "input peer unavailable", true);
                return;
            }
            String text = "[zapret test] message " + System.currentTimeMillis();
            setTestState(TEST_MESSAGE, STATUS_RUNNING, "sending to Saved Messages", true);
            appendLog("message test started");
            TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
            req.peer = peer;
            req.message = text;
            req.no_webpage = true;
            req.random_id = Utilities.random.nextLong();
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    setTestState(TEST_MESSAGE, STATUS_FAILED, error.text, true);
                    return;
                }
                processUpdatesIfNeeded(account, response);
                setTestState(TEST_MESSAGE, STATUS_SUCCESS, "saved to Saved Messages", true);
            }));
        });
    }

    public void runImageTest(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            long selfId = UserConfig.getInstance(account).getClientUserId();
            if (selfId == 0) {
                setTestState(TEST_IMAGE, STATUS_FAILED, "self dialog unavailable", true);
                return;
            }
            TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(selfId);
            if (peer == null) {
                setTestState(TEST_IMAGE, STATUS_FAILED, "input peer unavailable", true);
                return;
            }
            File imageFile = createTestImageFile();
            if (imageFile == null) {
                setTestState(TEST_IMAGE, STATUS_FAILED, "failed to create test image", true);
                return;
            }
            setTestState(TEST_IMAGE, STATUS_RUNNING, "uploading " + imageFile.getName(), true);
            appendLog("image test started");
            AccountInstance.getInstance(account).getFileLoader().uploadFile(imageFile.getAbsolutePath(), inputFile -> AndroidUtilities.runOnUIThread(() -> {
                if (inputFile == null) {
                    imageFile.delete();
                    setTestState(TEST_IMAGE, STATUS_FAILED, "upload failed", true);
                    return;
                }
                TLRPC.TL_messages_sendMedia req = new TLRPC.TL_messages_sendMedia();
                req.peer = peer;
                req.message = "[zapret test] image";
                req.random_id = Utilities.random.nextLong();
                TLRPC.TL_inputMediaUploadedPhoto media = new TLRPC.TL_inputMediaUploadedPhoto();
                media.file = inputFile;
                req.media = media;
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    imageFile.delete();
                    if (error != null) {
                        setTestState(TEST_IMAGE, STATUS_FAILED, error.text, true);
                        return;
                    }
                    processUpdatesIfNeeded(account, response);
                    setTestState(TEST_IMAGE, STATUS_SUCCESS, "image sent to Saved Messages", true);
                }));
            }));
        });
    }

    public void startCallTest(TLRPC.User user) {
        AndroidUtilities.runOnUIThread(() -> {
            String name = ContactsController.formatName(user.first_name, user.last_name);
            setTestState(TEST_CALL, STATUS_RUNNING, "starting call with " + name, true);
            appendLog("call test started with " + name);
            callTestToken++;
            int token = callTestToken;
            AndroidUtilities.runOnUIThread(() -> finishCallTestIfTimedOut(token), 25000L);
        });
    }

    public void failCallTest(String detail) {
        AndroidUtilities.runOnUIThread(() -> setTestState(TEST_CALL, STATUS_FAILED, detail, true));
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        ensureVoipListener();
        if (id == NotificationCenter.zapretSettingsChanged) {
            appendLog("settings changed: " + ZapretConfig.getSettingsSummary());
        } else if (id == NotificationCenter.zapretVpnStateChanged) {
            appendLog("local vpn: " + ZapretVpnController.getInstance().getStatusLabel());
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            updateSelectedAccountState();
            appendLog("network state: " + getConnectionStateLabel(selectedAccountState));
        } else if (id == NotificationCenter.voipServiceCreated || id == NotificationCenter.didStartedCall) {
            updateCurrentStates();
            appendLog("voip service active: " + getCallStateLabel(currentVoipState));
        }
    }

    @Override
    public void onStateChanged(int state) {
        AndroidUtilities.runOnUIThread(() -> {
            currentVoipState = state;
            appendLog("call state: " + getCallStateLabel(state));
            if (tests[TEST_CALL].status != STATUS_RUNNING) {
                return;
            }
            if (state == VoIPService.STATE_FAILED) {
                setTestState(TEST_CALL, STATUS_FAILED, "call failed", true);
            } else if (state == VoIPService.STATE_BUSY) {
                setTestState(TEST_CALL, STATUS_SUCCESS, "remote side is busy, signaling works", true);
            } else if (state == VoIPService.STATE_WAITING
                || state == VoIPService.STATE_RINGING
                || state == VoIPService.STATE_EXCHANGING_KEYS
                || state == VoIPService.STATE_WAIT_INIT
                || state == VoIPService.STATE_WAIT_INIT_ACK
                || state == VoIPService.STATE_ESTABLISHED
                || state == VoIPService.STATE_RECONNECTING
                || state == VoIPService.STATE_CREATING) {
                setTestState(TEST_CALL, STATUS_SUCCESS, getCallStateLabel(state), true);
            }
        });
    }

    private void finishCallTestIfTimedOut(int token) {
        if (token != callTestToken || tests[TEST_CALL].status != STATUS_RUNNING) {
            return;
        }
        setTestState(TEST_CALL, STATUS_FAILED, "no call progress", true);
    }

    private void updateCurrentStates() {
        updateSelectedAccountState();
        ensureVoipListener();
        currentVoipState = attachedVoipService != null ? attachedVoipService.getCallState() : 0;
        postUpdate();
    }

    private void updateSelectedAccountState() {
        int selectedAccount = UserConfig.selectedAccount;
        if (selectedAccount >= 0 && selectedAccount < UserConfig.MAX_ACCOUNT_COUNT) {
            selectedAccountState = ConnectionsManager.getInstance(selectedAccount).getConnectionState();
        }
    }

    private void ensureVoipListener() {
        VoIPService service = VoIPService.getSharedInstance();
        if (attachedVoipService == service) {
            return;
        }
        if (attachedVoipService != null) {
            attachedVoipService.unregisterStateListener(this);
        }
        attachedVoipService = service;
        if (attachedVoipService != null) {
            attachedVoipService.registerStateListener(this);
            currentVoipState = attachedVoipService.getCallState();
        } else {
            currentVoipState = 0;
        }
    }

    private void setTestState(int testType, int status, String detail, boolean log) {
        TestState state = tests[testType];
        state.status = status;
        state.detail = detail;
        state.updatedAt = System.currentTimeMillis();
        if (log) {
            appendLog(getTestName(testType) + ": " + getTestSummary(testType));
        } else {
            postUpdate();
        }
    }

    private String getTestName(int testType) {
        switch (testType) {
            case TEST_CONNECTION:
                return "connection test";
            case TEST_MESSAGE:
                return "message test";
            case TEST_IMAGE:
                return "image test";
            case TEST_CALL:
                return "call test";
            default:
                return "test";
        }
    }

    private void processUpdatesIfNeeded(int account, TLObject response) {
        if (response instanceof TLRPC.Updates) {
            MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
        }
    }

    private File createTestImageFile() {
        try {
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "zapret-tests");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File file = new File(dir, "zapret-test-" + System.currentTimeMillis() + ".png");
            Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.rgb(21, 31, 46));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(38, 166, 154));
            canvas.drawRect(24, 24, 232, 104, paint);
            paint.setColor(Color.rgb(255, 193, 7));
            canvas.drawCircle(74, 178, 38, paint);
            paint.setColor(Color.rgb(244, 67, 54));
            canvas.drawCircle(182, 178, 38, paint);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(10);
            canvas.drawLine(40, 220, 216, 220, paint);
            try (FileOutputStream stream = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            bitmap.recycle();
            return file;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private String getRecentLogPreview() {
        if (logLines.isEmpty()) {
            return LocaleController.getString(R.string.ZapretDebugLogEmpty);
        }
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, logLines.size() - 12);
        for (int i = start; i < logLines.size(); i++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(logLines.get(i));
        }
        return builder.toString();
    }

    private void appendLog(String message) {
        String time = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        logLines.add(time + "  " + message);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.remove(0);
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("zapret debug: " + message);
        }
        postUpdate();
    }

    private void postUpdate() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zapretDebugStateChanged));
    }

    private static class TestState {
        private int status;
        private String detail;
        private long updatedAt;
    }
}
