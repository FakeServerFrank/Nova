package com.nova.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NovaService extends Service {
    public interface Listener { void onEvent(String state, String text); }

    static volatile Listener listener;
    static volatile boolean running = false;

    private static final String[] JOKES = {
        "Qué hace una abeja en el gimnasio? Zumba.",
        "Cómo se despiden los químicos? Ácido un placer.",
        "Por qué los pájaros vuelan al sur? Porque caminando tardarían mucho."
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsReady, speaking, active;
    private PowerManager.WakeLock wl;
    private SharedPreferences prefs;

    private final Runnable timeout = new Runnable() {
        public void run() { active = false; post("idle", ""); }
    };

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "ACTIVATE".equals(i.getAction())) wake();
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = getSharedPreferences("nova", MODE_PRIVATE);
        startForeground(1, buildNotification());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:listen");
        wl.acquire();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "MX"));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) {}
                    public void onDone(String id) { main.post(() -> { speaking = false; listen(); }); }
                    public void onError(String id) { main.post(() -> { speaking = false; listen(); }); }
                });
                ttsReady = true;
            }
        });
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            post("reply", "Este teléfono no tiene reconocimiento de voz disponible.");
            stopSelf();
            return;
        }
        post("idle", "");
        listen();
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("nova", "Nova", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
            b = new Notification.Builder(this, "nova");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Nova está escuchando")
                .setContentText("Di “" + name() + "” para activarla.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }

    private String name() { return prefs.getString("name", "Nova"); }

    private void post(final String s, final String t) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onEvent(s, t);
        });
    }

    /* ---------- Escucha continua ---------- */
    private void listen() {
        if (!running || speaking) return;
        main.post(() -> {
            if (!running || speaking) return;
            try {
                if (sr == null) {
                    sr = SpeechRecognizer.createSpeechRecognizer(this);
                    sr.setRecognitionListener(rl);
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                sr.startListening(i);
            } catch (Exception e) {
                later(1500);
            }
        });
    }

    private void later(long ms) {
        main.postDelayed(() -> listen(), ms);
    }

    private String first(Bundle b) {
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (l != null && !l.isEmpty()) ? l.get(0) : null;
    }

    private final RecognitionListener rl = new RecognitionListener() {
        public void onReadyForSpeech(Bundle b) {}
        public void onBeginningOfSpeech() {}
        public void onRmsChanged(float v) {}
        public void onBufferReceived(byte[] b) {}
        public void onEndOfSpeech() {}
        public void onEvent(int t, Bundle b) {}

        public void onPartialResults(Bundle b) {
            if (active || speaking) return;
            String t = first(b);
            if (t != null && findName(norm(t)) != null) wake();
        }

        public void onResults(Bundle b) {
            String t = first(b);
            if (t == null || speaking) { later(100); return; }
            handle(norm(t));
        }

        public void onError(int e) {
            if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                post("reply", "Falta el permiso del micrófono. Abre Nova y acéptalo.");
                stopSelf();
                return;
            }
            later(e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1000 : 200);
        }
    };

    private void handle(String t) {
        String[] w = findName(t);
        if (!active) {
            if (w == null) { later(100); return; }
            if (isWakeWord(w[0])) { wake(); later(100); return; }
            run(w[0]);
            return;
        }
        String cmd = (w != null) ? w[0] : t;
        if (isWakeWord(cmd)) { later(100); return; }
        run(cmd);
    }

    private boolean isWakeWord(String s) {
        return s.length() < 2 || s.matches("(activate|activa|despierta|escucha|oye|hey|dime|hola)");
    }

    private void wake() {
        active = true;
        try {
            final ToneGenerator g = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            g.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            main.postDelayed(() -> g.release(), 400);
        } catch (Exception e) {}
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(60);
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, 8000);
        post("active", "");
    }

    private void run(String cmd) {
        active = false;
        main.removeCallbacks(timeout);
                String r = Actions.handle(this, cmd);
        say(r != null ? r : answer(cmd));
    }

    private void say(String text) {
        post("reply", text);
        post("idle", "");
        if (ttsReady) {
            speaking = true;
            try { if (sr != null) sr.cancel(); } catch (Exception e) {}
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova");
            main.postDelayed(() -> { if (speaking) { speaking = false; listen(); } }, 20000);
        } else {
            later(200);
        }
    }

    /* ---------- Reconocer el nombre ---------- */
    private static String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[¿?¡!.,]", "").trim();
    }

    private static int lev(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
        return d[a.length()][b.length()];
    }

    private String[] findName(String t) {
        String n = norm(name());
        String[] w = t.split("\\s+");
        int k = n.split("\\s+").length;
        for (int i = 0; i + k <= w.length; i++) {
            StringBuilder c = new StringBuilder();
            for (int j = i; j < i + k; j++) { if (j > i) c.append(' '); c.append(w[j]); }
            String cs = c.toString();
            if (cs.equals(n) || (n.length() >= 4 && lev(cs, n) <= 1)) {
                StringBuilder r = new StringBuilder();
                for (int j = i + k; j < w.length; j++) { if (r.length() > 0) r.append(' '); r.append(w[j]); }
                return new String[]{r.toString()};
            }
        }
        return null;
    }

    /* ---------- Comandos (sin IA) ---------- */
    private String answer(String t) {
        Matcher m;
        Locale es = new Locale("es", "MX");
        if (t.matches(".*(ayuda|que sabes|que puedes).*"))
            return "Puedo decirte la hora y la fecha, poner temporizadores, anotar cosas, abrir tus apps, buscar en internet, poner música, llamar, calcular y contar chistes.";
        if (t.matches(".*(como te llamas|tu nombre).*")) return "Me llamo " + name() + ".";
        if (t.matches("^(hola|buenas|hey).*")) return "Hola. ¿En qué te ayudo?";
        if (t.contains("gracias")) return "Para eso estoy.";
        if (t.matches(".*(que hora|la hora|hora es).*"))
            return "Son las " + new SimpleDateFormat("H:mm", es).format(new Date()) + ".";
        if (t.matches(".*(que dia|fecha|a cuanto estamos).*"))
            return "Hoy es " + new SimpleDateFormat("EEEE d 'de' MMMM", es).format(new Date()) + ".";

        m = Pattern.compile("(?:temporizador|timer|avisame)(?: de| en)? (\\d+) ?(segundo|seg|minuto|min|hora)").matcher(t);
        if (m.find()) {
            int q = Integer.parseInt(m.group(1));
            String u = m.group(2);
            long ms = (u.startsWith("h") ? 3600L : u.startsWith("m") ? 60L : 1L) * q * 1000L;
            final String lb = q + " " + u;
            main.postDelayed(() -> say("Tiempo cumplido: " + lb + "."), ms);
            return "Listo, te aviso en " + lb + ".";
        }
        m = Pattern.compile("^(?:recuerda|anota|nota)(?: que)? (.+)").matcher(t);
        if (m.find()) {
            String cur = prefs.getString("notes", "");
            prefs.edit().putString("notes", cur.isEmpty() ? m.group(1) : cur + "\n" + m.group(1)).apply();
            return "Anotado.";
        }
        if (t.matches(".*(mis notas|ver notas|que tengo anotado).*")) {
            String cur = prefs.getString("notes", "");
            return cur.isEmpty() ? "No tienes notas todavía." : cur;
        }
        if (t.matches(".*(borra|elimina|limpia) (las |mis )?notas.*")) {
            prefs.edit().putString("notes", "").apply();
            return "Notas borradas.";
        }
        m = Pattern.compile("^(?:llama|marca)(?: al| a)? ([\\d\\s+-]{5,})").matcher(t);
        if (m.find()) {
            String num = m.group(1).replaceAll("[^\\d+]", "");
            return open(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num)), "Marcando el " + num + ".");
        }
        m = Pattern.compile("^(?:abre|abrir|ir a|entra a)(?: la app de| la app| el| la)? (.+)").matcher(t);
        if (m.find()) return openApp(m.group(1));
        m = Pattern.compile("^(?:reproduce|pon|ponme|escuchar)(?: la cancion| musica de| musica)? (.+)").matcher(t);
        if (m.find())
            return open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(m.group(1)))),
                    "Buscando " + m.group(1) + " en YouTube.");
        m = Pattern.compile("^(?:busca|buscar|googlea|investiga)(?: en google)?(?: sobre)? (.+)").matcher(t);
        if (m.find())
            return open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(m.group(1)))),
                    "Buscando " + m.group(1) + ".");
        m = Pattern.compile("^(?:calcula |cuanto es |cuanto da )?(\\d+) ?(mas|menos|por|entre|x|\\+|-|\\*|/) ?(\\d+)$").matcher(t);
        if (m.find()) {
            double a = Double.parseDouble(m.group(1)), b = Double.parseDouble(m.group(3)), r;
            String o = m.group(2);
            if (o.equals("mas") || o.equals("+")) r = a + b;
            else if (o.equals("menos") || o.equals("-")) r = a - b;
            else if (o.equals("por") || o.equals("x") || o.equals("*")) r = a * b;
            else { if (b == 0) return "No se puede dividir entre cero."; r = a / b; }
            return "Da " + (r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.US, "%.2f", r)) + ".";
        }
        if (t.contains("dado")) return "Salió el " + (1 + new Random().nextInt(6)) + ".";
        if (t.contains("moneda") || t.contains("cara o cruz")) return new Random().nextBoolean() ? "Cara." : "Cruz.";
        if (t.contains("chiste")) return JOKES[new Random().nextInt(JOKES.length)];
        return "No entendí eso. Dime ayuda para ver lo que sé hacer.";
    }

    private String open(Intent i, String ok) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return ok;
        } catch (Exception e) {
            return "No pude abrir eso.";
        }
    }

    private String openApp(String q) {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        String pkg = null, label = null;
        for (ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            String l = r.loadLabel(pm).toString();
            String n = norm(l);
            if (n.equals(q)) { pkg = r.activityInfo.packageName; label = l; break; }
            if (pkg == null && n.contains(q)) { pkg = r.activityInfo.packageName; label = l; }
        }
        if (pkg == null) return "No encontré una app llamada " + q + ".";
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return "No pude abrir " + label + ".";
        return open(i, "Abriendo " + label + ".");
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (sr != null) { try { sr.destroy(); } catch (Exception e) {} }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (wl != null && wl.isHeld()) wl.release();
        Listener l = listener;
        if (l != null) l.onEvent("off", "");
        super.onDestroy();
    }
            }
                                                        
