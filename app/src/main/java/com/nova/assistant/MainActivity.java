package com.nova.assistant;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements NovaService.Listener {
    private TextView brand, clock, date, hello, status, card, stopBtn;
    private View orb;
    private GradientDrawable orbBg;
    private ObjectAnimator pulse;
    private boolean askedOverlay, askedBattery;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() { refreshHome(); h.postDelayed(this, 15000); }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    private TextView txt(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private String name() {
        return getSharedPreferences("nova", MODE_PRIVATE).getString("name", "Nova");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0F1F);
        root.setPadding(dp(22), dp(40), dp(22), dp(28));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        brand = txt(18, 0xFFE8EDF7);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setGravity(Gravity.START);
        head.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cfg = txt(14, 0xFF8C98B3);
        cfg.setText("Ajustes");
        cfg.setPadding(dp(14), dp(8), dp(14), dp(8));
        cfg.setOnClickListener(v -> settings());
        head.addView(cfg);
        root.addView(head);

        clock = txt(72, 0xFFE8EDF7);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c1.topMargin = dp(24);
        root.addView(clock, c1);
        date = txt(17, 0xFF8C98B3);
        root.addView(date);
        hello = txt(21, 0xFFE8EDF7);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c2.topMargin = dp(16);
        root.addView(hello, c2);

        orb = new View(this);
        orbBg = new GradientDrawable();
        orbBg.setShape(GradientDrawable.OVAL);
        orbBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        orbBg.setGradientRadius(dp(90));
        orbBg.setGradientCenter(0.35f, 0.3f);
        orb.setBackground(orbBg);
        orb.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(150));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(36);
        lp.bottomMargin = dp(18);
        root.addView(orb, lp);
        pulse = ObjectAnimator.ofPropertyValuesHolder(orb,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
        pulse.setDuration(1400);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.start();

        status = txt(16, 0xFF8C98B3);
        root.addView(status);

        stopBtn = txt(14, 0xFF8C98B3);
        stopBtn.setText("Detener escucha");
        stopBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(this, NovaService.class));
            h.postDelayed(() -> setState("idle"), 300);
        });
        root.addView(stopBtn);

        card = txt(16, 0xFFE8EDF7);
        card.setGravity(Gravity.START);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFF121A30);
        cbg.setCornerRadius(dp(16));
        cbg.setStroke(dp(1), 0xFF223052);
        card.setBackground(cbg);
        card.setVisibility(View.GONE);
        LinearLayout.LayoutParams c3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c3.topMargin = dp(18);
        root.addView(card, c3);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);
    }
      @Override
    protected void onResume() {
        super.onResume();
        NovaService.listener = this;
        refreshHome();
        h.postDelayed(tick, 15000);
        setState("idle");
    }

    @Override
    protected void onPause() {
        super.onPause();
        NovaService.listener = null;
        h.removeCallbacks(tick);
    }

    private void refreshHome() {
        Locale es = new Locale("es", "MX");
        Date n = new Date();
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        brand.setText(name());
        clock.setText(new SimpleDateFormat("H:mm", es).format(n));
        date.setText(new SimpleDateFormat("EEEE d 'de' MMMM", es).format(n));
        hello.setText(hr < 6 ? "Es de madrugada." : hr < 12 ? "Buenos días." : hr < 19 ? "Buenas tardes." : "Buenas noches.");
    }

    @Override
    public void onEvent(String s, String t) {
        if ("reply".equals(s)) {
            card.setVisibility(View.VISIBLE);
            card.setText(t);
            return;
        }
        setState(s);
    }

    private void setState(String s) {
        boolean on = NovaService.running;
        if (!on) {
            status.setText("Toca la esfera para activar");
            orbBg.setColors(new int[]{0xFF9AA3B5, 0xFF5B6478, 0xFF2A3042});
            pulse.setDuration(2400);
        } else if ("active".equals(s)) {
            status.setText("Te escucho…");
            orbBg.setColors(new int[]{0xFFFFE3D1, 0xFFFF8A5B, 0xFFC2335E});
            pulse.setDuration(600);
        } else {
            status.setText("Di “" + name() + "” o toca la esfera");
            orbBg.setColors(new int[]{0xFFC9FFF8, 0xFF4FD1C5, 0xFF2B5FD9});
            pulse.setDuration(1400);
        }
        stopBtn.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void toggle() {
        if (!NovaService.running) {
            begin();
        } else {
            Intent i = new Intent(this, NovaService.class);
            i.setAction("ACTIVATE");
            startService(i);
        }
    }

    private void begin() {
        ArrayList<String> need = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 7);
            return;
        }
        if (!Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true;
            Toast.makeText(this, "Activa «Mostrar sobre otras apps» para Nova y vuelve a tocar la esfera.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !askedBattery) {
            askedBattery = true;
            Toast.makeText(this, "Permite que Nova ignore el ahorro de batería y vuelve a tocar la esfera.", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {}
            return;
        }
        Intent i = new Intent(this, NovaService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        h.postDelayed(() -> setState("idle"), 500);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        begin();
    }

    private void settings() {
        final EditText et = new EditText(this);
        et.setText(name());
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Nombre del asistente")
                .setView(et)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.length() == 0) n = "Nova";
                    getSharedPreferences("nova", MODE_PRIVATE).edit().putString("name", n).apply();
                    refreshHome();
                    setState("idle");
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
