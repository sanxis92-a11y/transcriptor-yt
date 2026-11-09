package com.personal.yttranscript;

import android.text.Html;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Obtiene subtítulos (manuales o automáticos) de un vídeo de YouTube sin API key propia. */
public class TranscriptFetcher {

    public static class Track {
        public final String baseUrl, languageCode, name;
        public final boolean auto;
        Track(String baseUrl, String languageCode, String name, boolean auto) {
            this.baseUrl = baseUrl; this.languageCode = languageCode; this.name = name; this.auto = auto;
        }
        @Override public String toString() {
            return name + (auto ? " (automático)" : "");
        }
    }

    public static class VideoInfo {
        public final String id, title, author;
        public final List<Track> tracks;
        VideoInfo(String id, String title, String author, List<Track> tracks) {
            this.id = id; this.title = title; this.author = author; this.tracks = tracks;
        }
    }

    public static class Segment {
        public final double start, dur;
        public final String text;
        Segment(double start, double dur, String text) { this.start = start; this.dur = dur; this.text = text; }
    }

    private static final String UA_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final String UA_ANDROID =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";
    // Evita la página de consentimiento de cookies de la UE
    private static final String COOKIES = "SOCS=CAI; CONSENT=YES+1";

    // ---------- ID del vídeo ----------

    public static String extractVideoId(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.matches("[A-Za-z0-9_-]{11}")) return s;
        Pattern[] ps = {
                Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})"),
                Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})"),
                Pattern.compile("/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})")
        };
        for (Pattern p : ps) {
            Matcher m = p.matcher(s);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    // ---------- Lista de pistas ----------

    public static VideoInfo getInfo(String videoId) throws Exception {
        String html = httpGet("https://www.youtube.com/watch?v=" + videoId + "&hl=es", UA_WEB);
        if (html.contains("action=\"https://consent.youtube.com")) {
            throw new Exception("YouTube ha devuelto la página de consentimiento de cookies.");
        }

        List<Track> tracks = new ArrayList<>();
        String[] meta = {null, null};

        // 1) Cliente ANDROID de InnerTube (las URLs que devuelve suelen funcionar sin token extra)
        Matcher km = Pattern.compile("\"INNERTUBE_API_KEY\":\\s*\"([^\"]+)\"").matcher(html);
        if (km.find()) {
            try {
                JSONObject body = new JSONObject();
                JSONObject client = new JSONObject();
                client.put("clientName", "ANDROID");
                client.put("clientVersion", "20.10.38");
                client.put("androidSdkVersion", 34);
                client.put("hl", "es");
                body.put("context", new JSONObject().put("client", client));
                body.put("videoId", videoId);
                String resp = httpPostJson(
                        "https://www.youtube.com/youtubei/v1/player?key=" + km.group(1) + "&prettyPrint=false",
                        body.toString(), UA_ANDROID);
                JSONObject pr = new JSONObject(resp);
                tracks = parseTracks(pr);
                readMeta(pr, meta);
            } catch (Exception ignored) { }
        }

        // 2) Respaldo: ytInitialPlayerResponse de la propia página
        if (tracks.isEmpty()) {
            String json = extractJsonObject(html, "ytInitialPlayerResponse");
            if (json != null) {
                JSONObject pr = new JSONObject(json);
                tracks = parseTracks(pr);
                readMeta(pr, meta);
            }
        }
        if (meta[0] == null) {
            Matcher tm = Pattern.compile("<title>(.*?)</title>").matcher(html);
            if (tm.find()) meta[0] = Html.fromHtml(tm.group(1), Html.FROM_HTML_MODE_LEGACY)
                    .toString().replaceAll("\\s*-\\s*YouTube$", "").trim();
        }
        if (meta[0] == null || meta[0].isEmpty()) meta[0] = "Vídeo " + videoId;

        if (tracks.isEmpty()) {
            throw new Exception("Este vídeo no tiene subtítulos disponibles (ni automáticos).");
        }
        return new VideoInfo(videoId, meta[0], meta[1], tracks);
    }

    private static void readMeta(JSONObject player, String[] meta) {
        JSONObject vd = player.optJSONObject("videoDetails");
        if (vd == null) return;
        if (meta[0] == null && vd.optString("title").length() > 0) meta[0] = vd.optString("title");
        if (meta[1] == null && vd.optString("author").length() > 0) meta[1] = vd.optString("author");
    }

    /** Todos los IDs de vídeo distintos que aparezcan en un texto (uno o varios enlaces). */
    public static List<String> extractAllVideoIds(String input) {
        List<String> ids = new ArrayList<>();
        if (input == null) return ids;
        for (String tok : input.split("[\\s,;]+")) {
            String id = extractVideoId(tok);
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private static List<Track> parseTracks(JSONObject player) throws Exception {
        List<Track> out = new ArrayList<>();
        JSONObject caps = player.optJSONObject("captions");
        if (caps == null) return out;
        JSONObject r = caps.optJSONObject("playerCaptionsTracklistRenderer");
        if (r == null) return out;
        JSONArray arr = r.optJSONArray("captionTracks");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.getJSONObject(i);
            String name;
            JSONObject n = t.optJSONObject("name");
            if (n != null && n.has("simpleText")) name = n.getString("simpleText");
            else if (n != null && n.has("runs")) name = n.getJSONArray("runs").getJSONObject(0).getString("text");
            else name = t.optString("languageCode");
            out.add(new Track(
                    t.getString("baseUrl"),
                    t.optString("languageCode"),
                    name,
                    "asr".equals(t.optString("kind"))));
        }
        return out;
    }

    /** Elige: manual en idioma preferido > automático en idioma preferido > manual cualquiera > primero. */
    public static int pickBest(List<Track> tracks, String... langs) {
        for (String l : langs) for (int i = 0; i < tracks.size(); i++)
            if (!tracks.get(i).auto && tracks.get(i).languageCode.startsWith(l)) return i;
        for (String l : langs) for (int i = 0; i < tracks.size(); i++)
            if (tracks.get(i).languageCode.startsWith(l)) return i;
        for (int i = 0; i < tracks.size(); i++) if (!tracks.get(i).auto) return i;
        return 0;
    }

    // ---------- Descarga y parseo del texto ----------

    public static List<Segment> fetchSegments(Track track) throws Exception {
        String url = track.baseUrl.replace("&fmt=srv3", "").replace("&fmt=json3", "");
        String xml = httpGet(url, UA_ANDROID);
        if (xml.trim().isEmpty()) {
            // A veces la URL exige otro formato; probamos srv3 explícito
            xml = httpGet(url + "&fmt=srv3", UA_ANDROID);
        }
        if (xml.trim().isEmpty()) {
            throw new Exception("YouTube devolvió los subtítulos vacíos. Prueba de nuevo en un rato.");
        }
        return parseXml(xml);
    }

    private static List<Segment> parseXml(String xml) throws Exception {
        List<Segment> out = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        int ev = p.getEventType();
        double start = 0, dur = 0;
        StringBuilder sb = null;
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("text".equals(tag)) { // formato clásico: segundos
                    start = parseD(p.getAttributeValue(null, "start"));
                    dur = parseD(p.getAttributeValue(null, "dur"));
                    sb = new StringBuilder();
                } else if ("p".equals(tag)) { // formato srv3: milisegundos
                    start = parseD(p.getAttributeValue(null, "t")) / 1000.0;
                    dur = parseD(p.getAttributeValue(null, "d")) / 1000.0;
                    sb = new StringBuilder();
                }
            } else if (ev == XmlPullParser.TEXT && sb != null) {
                sb.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                String tag = p.getName();
                if (("text".equals(tag) || "p".equals(tag)) && sb != null) {
                    String t = clean(sb.toString());
                    if (!t.isEmpty()) out.add(new Segment(start, dur, t));
                    sb = null;
                }
            }
            ev = p.next();
        }
        if (out.isEmpty()) throw new Exception("No se pudo leer el texto de los subtítulos.");
        return out;
    }

    private static String clean(String s) {
        // Algunas pistas vienen doblemente escapadas (&amp;#39;)
        String t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
        return t.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static double parseD(String s) {
        try { return s == null ? 0 : Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    // ---------- Formato de salida ----------

    public static String format(List<Segment> segs, boolean timestamps) {
        StringBuilder sb = new StringBuilder();
        if (timestamps) {
            for (Segment s : segs) sb.append('[').append(ts(s.start)).append("] ").append(s.text).append('\n');
        } else {
            double lastEnd = -1;
            for (Segment s : segs) {
                if (lastEnd >= 0) sb.append(s.start - lastEnd > 2.0 ? "\n\n" : " ");
                sb.append(s.text);
                lastEnd = s.start + s.dur;
            }
        }
        return sb.toString().trim();
    }

    private static String ts(double sec) {
        int t = (int) sec, h = t / 3600, m = (t % 3600) / 60, s = t % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    // ---------- HTTP ----------

    private static String httpGet(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
        c.setRequestProperty("Cookie", COOKIES);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        return read(c);
    }

    private static String httpPostJson(String url, String json, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Cookie", COOKIES);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (code >= 400) throw new Exception("Error HTTP " + code + " al contactar con YouTube.");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }

    /** Extrae el objeto JSON que sigue a "nombre = {" contando llaves. */
    private static String extractJsonObject(String html, String varName) {
        int i = html.indexOf(varName + " = {");
        if (i < 0) i = html.indexOf(varName + "={");
        if (i < 0) return null;
        int start = html.indexOf('{', i);
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int j = start; j < html.length(); j++) {
            char ch = html.charAt(j);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
            } else if (ch == '"') inStr = true;
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return html.substring(start, j + 1);
        }
        return null;
    }
}
