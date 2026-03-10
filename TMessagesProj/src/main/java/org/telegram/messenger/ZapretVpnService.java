package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

import java.io.IOException;

public class ZapretVpnService extends VpnService {

    public static final String ACTION_START = "org.telegram.messenger.action.START_ZAPRET_VPN";

    private static final String CHANNEL_ID = "zapret_local_vpn";
    private static final int NOTIFICATION_ID = 4112;

    private ParcelFileDescriptor tunnelInterface;
    private ZapretTunnelRuntime tunnelRuntime;
    private boolean stopped;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (!ACTION_START.equals(action)) {
            stopVpn(null);
            return START_NOT_STICKY;
        }
        startVpn();
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        stopVpn("vpn permission revoked");
    }

    @Override
    public void onDestroy() {
        if (!stopped) {
            stopVpn(null);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    private void startVpn() {
        ZapretVpnController.getInstance().onServiceStarting();
        if (!ZapretConfig.shouldUseLocalVpn()) {
            stopVpn(null);
            return;
        }
        if (VpnService.prepare(this) != null) {
            stopVpn("vpn permission required");
            return;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(LocaleController.getString(R.string.ZapretLocalVpnNotificationStarting)));
            if (tunnelInterface == null) {
                Builder builder = new Builder();
                builder.setSession(LocaleController.getString(R.string.ZapretLocalVpnSession));
                builder.setMtu(1500);
                builder.addAddress("10.7.0.2", 32);
                builder.addRoute("0.0.0.0", 0);
                builder.addDnsServer("1.1.1.1");
                builder.addDnsServer("8.8.8.8");
                builder.setConfigureIntent(createConfigureIntent());
                builder.addAllowedApplication(getPackageName());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false);
                }
                tunnelInterface = builder.establish();
            }
            if (tunnelInterface == null) {
                stopVpn("failed to establish tun");
                return;
            }
            if (tunnelRuntime == null) {
                tunnelRuntime = new ZapretTunnelRuntime(this, tunnelInterface);
                tunnelRuntime.start();
            }
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(LocaleController.getString(R.string.ZapretLocalVpnNotificationText)));
            }
            ZapretVpnController.getInstance().onTunnelEstablished();
        } catch (Throwable e) {
            FileLog.e(e);
            stopVpn(e.getMessage());
        }
    }

    private PendingIntent createConfigureIntent() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setAction("org.telegram.messenger.OPEN_ZAPRET");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private Notification buildNotification(String text) {
        ensureNotificationChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(LocaleController.getString(R.string.ZapretLocalVpnNotificationTitle))
            .setContentText(text)
            .setSmallIcon(R.drawable.notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(createConfigureIntent())
            .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager == null || notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.ZapretLocalVpnNotificationTitle), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private void stopVpn(String error) {
        if (stopped) {
            return;
        }
        stopped = true;
        if (tunnelRuntime != null) {
            tunnelRuntime.stop();
            tunnelRuntime = null;
        }
        if (tunnelInterface != null) {
            try {
                tunnelInterface.close();
            } catch (IOException e) {
                FileLog.e(e);
            }
            tunnelInterface = null;
        }
        try {
            stopForeground(true);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        ZapretVpnController.getInstance().onServiceStopped(error);
        stopSelf();
    }

    void onRuntimeError(String error) {
        stopVpn(error);
    }
}
