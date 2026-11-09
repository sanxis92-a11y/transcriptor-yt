package com.personal.yttranscript;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 42;

    private EditText urlInput;
    private Button goBtn, collBtn;
    private Spinner trackSpinner;
    private CheckBox tsCheck;
    private ProgressBar progress;
    private TextView status, titleView, output;
    private LinearLayout actions;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Collection collection;
    private TranscriptFetcher.VideoInfo info;
    private TranscriptFetcher.Track currentTrack;
    private List<TranscriptFetcher.Segment> segments;
    private boolean ignoreSpinner = false;

    // Exportación pendiente (mientras el usuario elige dónde guardar)
    private List<Collection.Item> pendingItems;
    private boolean pendingPdf;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        collection = new Collection(this);
        buildUi();
        updateCollBtn();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Compartir un enlace desde la app de YouTube lo procesa directamente. */
    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String txt = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (txt != null) {
                urlInput.setText(txt.trim());
                start();
            }
        }
    }

    // ---------- UI ----------

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16), dp(16) + insets.getSystemWindowInsetTop(),
                    dp(16), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView appTitle = new TextView(this);
        appTitle.setText("Transcriptor YT");
        appTitle.setTextSize(22);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setTextColor(Color.parseColor("#202124"));
        top.addView(appTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        collBtn = smallBtn("Colección (0)", v -> showCollection());
        top.addView(collBtn);
        root.addView(top);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        urlInput = new EditText(this);
        urlInput.setHint("Enlace de YouTube (o varios, uno por línea)");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setMaxLines(4);
        row.addView(urlInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(smallBtn("Pegar", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (t != null) {
                    String cur = urlInput.getText().toString().trim();
                    urlInput.setText(cur.isEmpty() ? t.toString().trim() : cur + "\n" + t.toString().trim());
                    urlInput.setSelection(urlInput.getText().length());
                }
            }
        }));
        root.addView(row);

        goBtn = new Button(this);
        goBtn.setText("Obtener transcripción");
        goBtn.setOnClickListener(v -> start());
        root.addView(goBtn, matchWrap());

        trackSpinner = new Spinner(this);
        trackSpinner.setVisibility(View.GONE);
        trackSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (ignoreSpinner) { ignoreSpinner = false; return; }
                loadTrack(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        root.addView(trackSpinner);

        tsCheck = new CheckBox(this);
        tsCheck.setText("Mostrar marcas de tiempo");
        tsCheck.setVisibility(View.GONE);
        tsCheck.setOnCheckedChangeListener((c, checked) -> render());
        root.addView(tsCheck);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        status = new TextView(this);
        status.setVisibility(View.GONE);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(Color.parseColor("#202124"));
        titleView.setTextIsSelectable(true);
        titleView.setPadding(0, dp(8), 0, 0);
        content.addView(titleView);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(15);
        output.setLineSpacing(0, 1.2f);
        output.setTextColor(Color.parseColor("#202124"));
        output.setPadding(0, dp(8), 0, dp(8));
        content.addView(output);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setVisibility(View.GONE);
        actions.addView(smallBtn("+ Colección", v -> addCurrentToCollection()), weight());
        actions.addView(smallBtn("Copiar", v -> copy()), weight());
        actions.addView(smallBtn("Compartir", v -> share()), weight());
        actions.addView(smallBtn("Exportar", v -> {
            List<Collection.Item> one = new ArrayList<>();
            one.add(currentItem());
            askFormatAndExport(one, info.title);
        }), weight());
        root.addView(actions);

        setContentView(root);
    }

    private Button smallBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        goBtn.setEnabled(!busy);
        trackSpinner.setEnabled(!busy);
        collBtn.setEnabled(!busy);
    }

    private void showStatus(String msg, boolean error) {
        status.setText(msg);
        status.setTextColor(Color.parseColor(error ? "#B3261E" : "#5F6368"));
        status.setVisibility(View.VISIBLE);
    }

    private void updateCollBtn() { collBtn.setText("Colección (" + collection.size() + ")"); }

    // ---------- Obtener transcripciones ----------

    private void start() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        status.setVisibility(View.GONE);

        List<String> ids = TranscriptFetcher.extractAllVideoIds(urlInput.getText().toString());
        if (ids.isEmpty()) { showStatus("No reconozco ningún enlace de YouTube.", true); return; }
        if (ids.size() == 1) fetchOne(ids.get(0));
        else fetchBatch(ids);
    }

    private void fetchOne(String id) {
        titleView.setText("");
        output.setText("");
        actions.setVisibility(View.GONE);
        tsCheck.setVisibility(View.GONE);
        trackSpinner.setVisibility(View.GONE);
        setBusy(true);
        exec.execute(() -> {
            try {
                TranscriptFetcher.VideoInfo vi = TranscriptFetcher.getInfo(id);
                int best = TranscriptFetcher.pickBest(vi.tracks, "es", "en");
                List<TranscriptFetcher.Segment> s = TranscriptFetcher.fetchSegments(vi.tracks.get(best));
                ui.post(() -> {
                    info = vi;
                    currentTrack = vi.tracks.get(best);
                    segments = s;
                    ArrayAdapter<TranscriptFetcher.Track> ad =
                            new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, vi.tracks);
                    if (vi.tracks.size() > 1) {
                        ignoreSpinner = true;
                        trackSpinner.setAdapter(ad);
                        trackSpinner.setSelection(best, false);
                        trackSpinner.setVisibility(View.VISIBLE);
                    }
                    tsCheck.setVisibility(View.VISIBLE);
                    actions.setVisibility(View.VISIBLE);
                    render();
                    setBusy(false);
                    if (collection.contains(vi.id)) showStatus("Este vídeo ya está en tu colección.", false);
                });
            } catch (Exception e) {
                ui.post(() -> { setBusy(false); showStatus(msg(e), true); });
            }
        });
    }

    private void loadTrack(int pos) {
        if (info == null || pos >= info.tracks.size()) return;
        status.setVisibility(View.GONE);
        setBusy(true);
        TranscriptFetcher.Track tr = info.tracks.get(pos);
        exec.execute(() -> {
            try {
                List<TranscriptFetcher.Segment> s = TranscriptFetcher.fetchSegments(tr);
                ui.post(() -> { currentTrack = tr; segments = s; render(); setBusy(false); });
            } catch (Exception e) {
                ui.post(() -> { setBusy(false); showStatus(msg(e), true); });
            }
        });
    }

    /** Varios enlaces: se transcriben uno a uno y se van añadiendo a la colección. */
    private void fetchBatch(List<String> ids) {
        actions.setVisibility(View.GONE);
        tsCheck.setVisibility(View.GONE);
        trackSpinner.setVisibility(View.GONE);
        titleView.setText("");
        output.setText("");
        setBusy(true);
        boolean ts = tsCheck.isChecked();
        exec.execute(() -> {
            StringBuilder log = new StringBuilder();
            int ok = 0;
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                final int n = i + 1;
                ui.post(() -> showStatus("Procesando " + n + " de " + ids.size() + "…", false));
                try {
                    TranscriptFetcher.VideoInfo vi = TranscriptFetcher.getInfo(id);
                    TranscriptFetcher.Track tr = vi.tracks.get(TranscriptFetcher.pickBest(vi.tracks, "es", "en"));
                    String text = TranscriptFetcher.format(TranscriptFetcher.fetchSegments(tr), ts);
                    ui.post(() -> { collection.put(vi.id, vi.title, vi.author, tr.languageCode, text); updateCollBtn(); });
                    log.append("✓ ").append(vi.title).append('\n');
                    ok++;
                } catch (Exception e) {
                    log.append("✗ ").append(id).append(" — ").append(msg(e)).append('\n');
                }
            }
            final int okF = ok;
            ui.post(() -> {
                setBusy(false);
                showStatus(okF + " de " + ids.size() + " añadidas a la colección.", okF < ids.size());
                titleView.setText("Resultado");
                output.setText(log.toString().trim());
                urlInput.setText("");
            });
        });
    }

    private String msg(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "Sin conexión a internet.";
        String m = e.getMessage();
        return m != null ? m : e.toString();
    }

    private void render() {
        if (segments == null || info == null) return;
        titleView.setText(info.title + (info.author != null ? "\n" : ""));
        if (info.author != null) {
            titleView.append(info.author);
        }
        output.setText(TranscriptFetcher.format(segments, tsCheck.isChecked()));
    }

    private Collection.Item currentItem() {
        return new Collection.Item(info.id, info.title, info.author == null ? "" : info.author,
                currentTrack == null ? "" : currentTrack.languageCode, output.getText().toString());
    }

    // ---------- Acciones ----------

    private void addCurrentToCollection() {
        boolean existed = collection.contains(info.id);
        Collection.Item it = currentItem();
        collection.put(it.videoId, it.title, it.author, it.language, it.text);
        updateCollBtn();
        Toast.makeText(this, existed ? "Actualizado en la colección" : "Añadido a la colección",
                Toast.LENGTH_SHORT).show();
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Transcripción",
                Exporter.toTxt(Collections.singletonList(currentItem()))));
        Toast.makeText(this, "Copiado (con título)", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, info.title);
        i.putExtra(Intent.EXTRA_TEXT, Exporter.toTxt(Collections.singletonList(currentItem())));
        startActivity(Intent.createChooser(i, "Compartir transcripción"));
    }

    // ---------- Colección ----------

    private void showCollection() {
        if (collection.size() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Colección vacía")
                    .setMessage("Obtén una transcripción y pulsa «+ Colección», o pega varios enlaces (uno por línea) para añadirlos todos de golpe.")
                    .setPositiveButton("Vale", null).show();
            return;
        }
        List<Collection.Item> items = collection.items();
        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) names[i] = (i + 1) + ". " + items.get(i).title;

        new AlertDialog.Builder(this)
                .setTitle("Colección (" + items.size() + ") — toca para ver o quitar")
                .setItems(names, (d, which) -> itemOptions(which))
                .setPositiveButton("Exportar todo", (d, w) ->
                        askFormatAndExport(new ArrayList<>(collection.items()), "transcripciones"))
                .setNeutralButton("Vaciar", (d, w) -> new AlertDialog.Builder(this)
                        .setMessage("¿Borrar las " + collection.size() + " transcripciones de la colección?")
                        .setPositiveButton("Borrar", (d2, w2) -> { collection.clear(); updateCollBtn(); })
                        .setNegativeButton("Cancelar", null).show())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void itemOptions(int index) {
        Collection.Item it = collection.items().get(index);
        new AlertDialog.Builder(this)
                .setTitle(it.title)
                .setItems(new String[]{"Ver", "Quitar de la colección"}, (d, w) -> {
                    if (w == 0) {
                        info = null; segments = null;
                        actions.setVisibility(View.GONE);
                        tsCheck.setVisibility(View.GONE);
                        trackSpinner.setVisibility(View.GONE);
                        status.setVisibility(View.GONE);
                        titleView.setText(it.title + (it.author.isEmpty() ? "" : "\n" + it.author));
                        output.setText(it.text);
                    } else {
                        collection.remove(index);
                        updateCollBtn();
                        showCollection();
                    }
                })
                .show();
    }

    // ---------- Exportar ----------

    private void askFormatAndExport(List<Collection.Item> items, String baseName) {
        new AlertDialog.Builder(this)
                .setTitle("Exportar " + (items.size() == 1 ? "transcripción" : items.size() + " transcripciones"))
                .setItems(new String[]{"PDF", "TXT"}, (d, w) -> {
                    pendingItems = items;
                    pendingPdf = (w == 0);
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(pendingPdf ? "application/pdf" : "text/plain");
                    i.putExtra(Intent.EXTRA_TITLE, safeName(baseName) + (pendingPdf ? ".pdf" : ".txt"));
                    startActivityForResult(i, REQ_EXPORT);
                })
                .show();
    }

    private static String safeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.isEmpty() ? "transcripcion" : n;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null
                || pendingItems == null) return;
        Uri uri = data.getData();
        List<Collection.Item> items = pendingItems;
        boolean pdf = pendingPdf;
        pendingItems = null;
        setBusy(true);
        exec.execute(() -> {
            String result;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (pdf) Exporter.writePdf(items, os);
                else Exporter.writeTxt(items, os);
                result = "Exportado";
            } catch (Exception e) {
                result = "No se pudo exportar: " + msg(e);
            }
            String r = result;
            ui.post(() -> { setBusy(false); Toast.makeText(this, r, Toast.LENGTH_LONG).show(); });
        });
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}
