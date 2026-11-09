package com.personal.yttranscript;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Colección de transcripciones guardada en el móvil (sobrevive a cerrar la app). */
public class Collection {

    public static class Item {
        public final String videoId, title, author, language, text;
        Item(String videoId, String title, String author, String language, String text) {
            this.videoId = videoId; this.title = title; this.author = author;
            this.language = language; this.text = text;
        }
        public String url() { return "https://youtu.be/" + videoId; }
    }

    private final File file;
    private final List<Item> items = new ArrayList<>();

    public Collection(Context ctx) {
        file = new File(ctx.getFilesDir(), "coleccion.json");
        load();
    }

    public List<Item> items() { return items; }
    public int size() { return items.size(); }

    public boolean contains(String videoId) {
        for (Item i : items) if (i.videoId.equals(videoId)) return true;
        return false;
    }

    /** Añade o sustituye (si el vídeo ya estaba). */
    public void put(String videoId, String title, String author, String language, String text) {
        Item n = new Item(videoId, title, author == null ? "" : author, language, text);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).videoId.equals(videoId)) { items.set(i, n); save(); return; }
        }
        items.add(n);
        save();
    }

    public void remove(int index) { items.remove(index); save(); }
    public void clear() { items.clear(); save(); }

    private void load() {
        if (!file.exists()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            JSONArray arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                items.add(new Item(o.getString("id"), o.getString("title"), o.optString("author"),
                        o.optString("lang"), o.getString("text")));
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item i : items) {
                arr.put(new JSONObject().put("id", i.videoId).put("title", i.title)
                        .put("author", i.author).put("lang", i.language).put("text", i.text));
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }
}
