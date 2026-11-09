package com.nova.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Actions {
    private Actions() {}

    private static final class App { String label, key, pkg; }
    private static List<App> cache = new ArrayList<App>();
    private static long loaded = 0;

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

    private static boolean start(Context c, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String view(Context c, String url, String ok) {
        return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(url))) ? ok : "No pude abrir eso.";
    }

    /* ---------- Apps instaladas (con memoria para abrir rápido) ---------- */
    private static synchronized List<App> apps(Context c) {
        if (cache.isEmpty() || System.currentTimeMillis() - loaded > 600000) {
            PackageManager pm = c.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            List<App> l = new ArrayList<App>();
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                App a = new App();
                a.label = r.loadLabel(pm).toString();
                a.key = norm(a.label).replace(" ", "");
                a.pkg = r.activityInfo.packageName;
                l.add(a);
            }
            cache = l;
            loaded = System.currentTimeMillis();
        }
        return cache;
    }

    private static String alias(String q) {
        switch (q) {
            case "whats": case "wasap": case "wats": case "guasap": return "whatsapp";
            case "face": return "facebook";
            case "insta": return "instagram";
            case "yutu": case "youtub": return "youtube";
            case "tik": return "tiktok";
            default: return q;
        }
    }

    private static App find(Context c, String raw, boolean exactOnly) {
        String q = alias(raw.replace(" ", ""));
        if (q.length() < 2) return null;
        App best = null;
        int bs = 0;
        for (App a : apps(c)) {
            int s = 0;
            if (a.key.equals(q)) s = 100;
            else if (!exactOnly) {
                if (a.key.startsWith(q)) s = 80;
                else if (a.key.length() >= 3 && q.startsWith(a.key)) s = 70;
                else if (a.key.contains(q)) s = 60;
                else if (q.length() >= 4 && lev(a.key, q) <= 2) s = 40;
            }
            if (s > 0 && (s > bs || (s == bs && best != null && a.key.length() < best.key.length()))) {
                bs = s;
                best = a;
            }
        }
        return best;
    }

    private static String launch(Context c, App a) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (i == null || !start(c, i)) return "No pude abrir " + a.label + ".";
        return "Abriendo " + a.label + ".";
    }

    /* ---------- Linterna, volumen, bateria ---------- */
    private static String torch(Context c, boolean on) {
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean f = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lens = ch.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f && lens != null && lens == CameraCharacteristics.LENS_FACING_BACK) {
                    cm.setTorchMode(id, on);
                    return on ? "Linterna encendida." : "Linterna apagada.";
                }
            }
        } catch (Exception e) {}
        return "No pude usar la linterna.";
    }

    private static String volume(Context c, int dir, boolean max) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (max) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI);
            return "Volumen al máximo.";
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
        return dir == AudioManager.ADJUST_RAISE ? "Subiendo el volumen." : "Bajando el volumen.";
    }

    private static String alarm(Context c, String t) {
        if (t.matches(".*(mis alarmas|ver alarmas|muestra.* alarmas).*")) {
            return start(c, new Intent(AlarmClock.ACTION_SHOW_ALARMS)) ? "Estas son tus alarmas." : "No pude abrir las alarmas.";
        }
        Matcher m = Pattern.compile("(?:a las|para las|a la|para la) (\\d{1,2})(?::(\\d{2}))?( y media| y cuarto| menos cuarto)?").matcher(t);
        if (!m.find()) return "¿A qué hora? Di, por ejemplo, pon una alarma a las 7.";
        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        String ex = m.group(3) == null ? "" : m.group(3);
        if (ex.equals(" y media")) min = 30;
        else if (ex.equals(" y cuarto")) min = 15;
        else if (ex.equals(" menos cuarto")) { h = h - 1; min = 45; }
        boolean pm = t.contains("tarde") || t.contains("noche");
        if (pm && h < 12) h += 12;
        if (!pm && t.contains("manana") && h == 12) h = 0;
        if (h < 0 || h > 23 || min > 59) return "Esa hora no es válida.";
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Nova");
        if (!start(c, i)) return "No pude poner la alarma.";
        return "Alarma puesta a las " + String.format(Locale.US, "%d:%02d", h, min) + ".";
    }
      /* ---------- Entrada: devuelve null si no es para mí ---------- */
    static String handle(Context c, String t) {
        Matcher m;

        if (t.matches(".*(cuanta|que|nivel de) bateria.*")) {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            return "Tienes " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " por ciento de batería.";
        }
        if (t.contains("alarma")) return alarm(c, t);
        if (t.contains("linterna") || t.contains("lampara")) {
            return torch(c, !t.matches(".*(apaga|desactiva|quita).*"));
        }
        if (t.matches(".*(sube|aumenta|mas) (el )?volumen.*")) return volume(c, AudioManager.ADJUST_RAISE, false);
        if (t.matches(".*(baja|disminuye|menos) (el )?volumen.*")) return volume(c, AudioManager.ADJUST_LOWER, false);
        if (t.matches(".*volumen (al )?maximo.*")) return volume(c, 0, true);

        m = Pattern.compile("(?:abre|abrir|ve a|entra a|muestra|enciende|apaga|activa|desactiva|prende)(?: los| las| el| la)? (ajustes|configuracion|wifi|wi fi|bluetooth|brillo|ubicacion|gps|ahorro de bateria)").matcher(t);
        if (m.find()) {
            String k = m.group(1);
            String a = Settings.ACTION_SETTINGS;
            if (k.startsWith("wi")) a = Settings.ACTION_WIFI_SETTINGS;
            else if (k.equals("bluetooth")) a = Settings.ACTION_BLUETOOTH_SETTINGS;
            else if (k.equals("brillo")) a = Settings.ACTION_DISPLAY_SETTINGS;
            else if (k.equals("ubicacion") || k.equals("gps")) a = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
            else if (k.startsWith("ahorro")) a = Settings.ACTION_BATTERY_SAVER_SETTINGS;
            return start(c, new Intent(a)) ? "Abriendo los ajustes de " + k + "." : "No pude abrir los ajustes.";
        }

        if (t.matches(".*(toma|saca|haz|hazme) (una )?foto.*") || t.matches("^(abre|abrir) (la )?camara$")) {
            return start(c, new Intent(MediaStore.ACTION_IMAGE_CAPTURE)) ? "Abriendo la cámara." : "No pude abrir la cámara.";
        }
        if (t.matches(".*(graba|grabar) (un )?video.*")) {
            return start(c, new Intent(MediaStore.ACTION_VIDEO_CAPTURE)) ? "Abriendo la cámara de video." : "No pude abrir la cámara.";
        }

        m = Pattern.compile("^(?:ruta a|como llego a|navega a|navegar a|llevame a)(?: la| el)? (.+)").matcher(t);
        if (m.find()) {
            String p = m.group(1);
            Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(p)));
            if (start(c, nav)) return "Navegando a " + p + ".";
            return view(c, "https://www.google.com/maps/search/" + Uri.encode(p), "Buscando " + p + " en el mapa.");
        }

        m = Pattern.compile("(?:manda|envia|escribe).*?(?:diciendo|que diga|que dice) (.+)").matcher(t);
        if (m.find() && t.matches(".*(whatsapp|wasap|mensaje).*")) {
            return view(c, "https://api.whatsapp.com/send?text=" + Uri.encode(m.group(1)),
                    "Abriendo WhatsApp con tu mensaje. Elige a quién enviarlo.");
        }

        m = Pattern.compile("^(?:reproduce|pon|ponme|escuchar)(?: la cancion| musica de| musica)? (.+?) en spotify$").matcher(t);
        if (m.find()) return view(c, "https://open.spotify.com/search/" + Uri.encode(m.group(1)), "Buscando " + m.group(1) + " en Spotify.");
        m = Pattern.compile("^(?:busca|buscar)(?: en google)? (.+?) en youtube$").matcher(t);
        if (m.find()) return view(c, "https://www.youtube.com/results?search_query=" + Uri.encode(m.group(1)), "Buscando " + m.group(1) + " en YouTube.");
        m = Pattern.compile("^(?:busca|buscar)(?: en google)? (.+?) en (?:el mapa|mapas)$").matcher(t);
        if (m.find()) return view(c, "https://www.google.com/maps/search/" + Uri.encode(m.group(1)), "Buscando " + m.group(1) + " en el mapa.");

        m = Pattern.compile("^(?:abre|abrir|abreme|lanza|ejecuta|inicia|entra a|ve a|ir a)(?: la app de| la app| el| la| mi)? (.+)").matcher(t);
        if (m.find()) {
            App a = find(c, m.group(1), false);
            return a == null ? "No encontré una app llamada " + m.group(1) + "." : launch(c, a);
        }
        App ex = find(c, t, true);
        if (ex != null && t.length() >= 3) return launch(c, ex);

        if (t.matches("^(quien|donde|cuando|cual|como se|por que|que es|que son|que significa|cuanto mide|cuanto pesa|dime).*")) {
            return view(c, "https://www.google.com/search?q=" + Uri.encode(t), "Te muestro lo que encontré en Google.");
        }
        return null;
    }
}
