package org.telegram.messenger;

import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;

public class ZapretController implements NotificationCenter.NotificationCenterDelegate, VoIPService.StateListener {
    private static final ZapretController INSTANCE = new ZapretController();

    private boolean rotationScheduled;
    private String rotationReason;
    private VoIPService attachedVoipService;
    private int currentVoipState;

    private final Runnable rotateRunnable = () -> {
        rotationScheduled = false;
        if (!ZapretConfig.shouldAutoRotateBuiltInProfiles()) {
            return;
        }

        int previousStrategy = ZapretConfig.getSelectedStrategy();
        ZapretConfig.selectNextStrategy();
        String previousReason = rotationReason;
        rotationReason = null;
        if (BuildVars.LOGS_ENABLED && previousStrategy != ZapretConfig.getSelectedStrategy()) {
            FileLog.d("zapret rotate strategy " + previousStrategy + " -> " + ZapretConfig.getSelectedStrategy());
        }
        ZapretDiagnosticsController.getInstance().onRotationPerformed(previousStrategy, ZapretConfig.getSelectedStrategy(), previousReason);

        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            ConnectionsManager connectionsManager = ConnectionsManager.getInstance(a);
            connectionsManager.checkConnection();
            connectionsManager.resumeNetworkMaybe();
        }
    };

    public static void init() {
        INSTANCE.initInternal();
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.voipServiceCreated);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.zapretSettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didStartedCall);
        ensureVoipListener();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        ensureVoipListener();
        if (id == NotificationCenter.zapretSettingsChanged) {
            reevaluateRotation(UserConfig.selectedAccount);
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            reevaluateRotation(account);
        } else if (id == NotificationCenter.didStartedCall || (id == NotificationCenter.voipServiceCreated && account == UserConfig.selectedAccount)) {
            reevaluateRotation(UserConfig.selectedAccount);
        }
    }

    private void reevaluateRotation(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !ZapretConfig.shouldAutoRotateBuiltInProfiles()) {
            cancelRotation("disabled");
            return;
        }

        String reason = getRotationReason(account);
        if (reason != null) {
            scheduleRotation(reason);
            return;
        }
        cancelRotation("stable");
    }

    private String getRotationReason(int account) {
        if (ZapretConfig.shouldAutoRotateBuiltInProfilesForCalls() && isVoipConnectingState(currentVoipState)) {
            return "call:" + ZapretDiagnosticsController.getCallStateLabel(currentVoipState);
        }

        if (ZapretConfig.shouldAutoRotateBuiltInProfilesForMessages()) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (state == ConnectionsManager.ConnectionStateConnecting || state == ConnectionsManager.ConnectionStateConnectingToProxy) {
                return "network:" + ZapretDiagnosticsController.getConnectionStateLabel(state);
            }
        }

        return null;
    }

    private void scheduleRotation(String reason) {
        if (rotationScheduled && reason != null && reason.equals(rotationReason)) {
            return;
        }
        cancelRotation(null);
        rotationScheduled = true;
        rotationReason = reason;
        ZapretDiagnosticsController.getInstance().onRotationScheduled(reason, ZapretConfig.getRotationTimeoutSeconds());
        AndroidUtilities.runOnUIThread(rotateRunnable, ZapretConfig.getRotationTimeoutSeconds() * 1000L);
    }

    private void cancelRotation(String reason) {
        if (!rotationScheduled) {
            if (reason != null) {
                rotationReason = null;
                ZapretDiagnosticsController.getInstance().onRotationCancelled(reason);
            }
            return;
        }
        rotationScheduled = false;
        AndroidUtilities.cancelRunOnUIThread(rotateRunnable);
        rotationReason = null;
        ZapretDiagnosticsController.getInstance().onRotationCancelled(reason);
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
        currentVoipState = service != null ? service.getCallState() : 0;
        if (attachedVoipService != null) {
            attachedVoipService.registerStateListener(this);
        }
    }

    private boolean isVoipConnectingState(int state) {
        return state == VoIPService.STATE_REQUESTING
            || state == VoIPService.STATE_CREATING
            || state == VoIPService.STATE_WAIT_INIT
            || state == VoIPService.STATE_WAIT_INIT_ACK
            || state == VoIPService.STATE_EXCHANGING_KEYS
            || state == VoIPService.STATE_RECONNECTING
            || state == VoIPService.STATE_WAITING;
    }

    @Override
    public void onStateChanged(int state) {
        currentVoipState = state;
        reevaluateRotation(UserConfig.selectedAccount);
    }
}
