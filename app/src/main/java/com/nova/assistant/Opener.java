package com.nova.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.AlarmClock;

final class Opener {
    private Opener() {}

    static void go(Context c, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        if (AlarmClock.ACTION_SET_ALARM.equals(i.getAction())) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("nova_open", "Abrir apps", NotificationManager.IMPORTANCE_HIGH));
            }
            PendingIntent pi = PendingIntent.getActivity(c, 1, new Intent(i),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, "nova_open")
                    : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("Nova")
                    .setContentText("Toca aquí si no se abrió solo")
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(6000);
            nm.notify(2, b.build());
        } catch (Exception e) {}
    }
}