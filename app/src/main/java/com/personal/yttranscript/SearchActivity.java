package com.personal.yttranscript;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Busca vídeos en YouTube, permite marcarlos y los transcribe todos a la colección. */
public class SearchActivity extends Activity {

    public static final String EXTRA_OPEN_COLLECTION = "open_collection";

    static final String[] LANG_NAMES = {"Cualquiera", "Español", "Inglés", "Francés", "Italiano",
            "Portugués", "Alemán", "Catalán"};
    static final String[] LANG_CODES = {"", "es", "en", "fr", "it", "pt", "de", "ca"};
    static final String[] LANG_GL = {"", "ES", "US", "FR", "IT", "PT", "DE", "ES"};

    private static final String[] DATE_NAMES = {"Cualquier fecha", "Hoy", "Esta semana", "Este mes", "Este año"};
    private static final int[] DATE_VALS = {0, 2, 3, 4, 5};
    private static final String[] DUR_NAMES = {"Cualquier duración", "Menos de 4 min", "4 - 20 min", "Más de 20 min"};
    private static final int[] DUR_VALS = {0, 1, 3, 2};
    private static final String[] SORT_NAMES = {"Relevancia", "Más recientes", "Más vistos"};
    private static final int[] SORT_VALS = {0, 2, 3};

    private EditText query;
    private Spinner langSp, dateSp, durSp, sortSp;
    private CheckBox subsCheck, translateCheck, tsCheck;
    private Button searchBtn, moreBtn, allBtn, goBtn;
    private ProgressBar progress;
    private TextView status;
    private ListView list;

    private final List<TranscriptFetcher.SearchResult> results = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private String continuation;
    private boolean busy;
    private volatile boolean cancelled;
    private ResultAdapter adapter;
    private SharedPreferences prefs;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ajustes", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FAFAFA"));
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(16), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = btn("←", v -> finish());
        top.addView(back);
        TextView title = new TextView(this);
        title.setText("Buscar vídeos");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#202124"));
        title.setPadding(dp(8), 0, 0, 0);
        top.addView(title);
        root.addView(top);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setHint("Ej: Fórmula 1");
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setText(prefs.getString("ultima_busqueda", ""));
        query.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                newSearch();
                return true;
            }
            return false;
        });
        row.addView(query, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        searchBtn = btn("Buscar", v -> newSearch());
        row.addView(searchBtn);
        root.addView(row);

        LinearLayout f1 = new LinearLayout(this);
        langSp = spinner(LANG_NAMES, prefs.getInt("idioma", 1));
        sortSp = spinner(SORT_NAMES, prefs.getInt("orden", 0));
        f1.addView(langSp, half());
        f1.addView(sortSp, half());
        root.addView(f1);

        LinearLayout f2 = new LinearLayout(this);
        dateSp = spinner(DATE_NAMES, prefs.getInt("fecha", 0));
        durSp = spinner(DUR_NAMES, prefs.getInt("duracion", 0));
        f2.addView(dateSp, half());
        f2.addView(durSp, half());
        root.addView(f2);

        subsCheck = check("Solo vídeos con subtítulos", prefs.getBoolean("solo_subs", true));
        translateCheck = check("Traducir si no hay subtítulos en ese idioma", prefs.getBoolean("traducir", true));
        tsCheck = check("Incluir marcas de tiempo", prefs.getBoolean("marcas", false));
        root.addView(subsCheck);
        root.addView(translateCheck);
        root.addView(tsCheck);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#5F6368"));
        status.setVisibility(View.GONE);
        root.addView(status);

        list = new ListView(this);
        adapter = new ResultAdapter();
        moreBtn = btn("Cargar más resultados", v -> loadMore());
        moreBtn.setVisibility(View.GONE);
        list.addFooterView(moreBtn);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= results.size() || busy) return;
            String vid = results.get(pos).videoId;
            if (!selected.remove(vid)) selected.add(vid);
            adapter.notifyDataSetChanged();
            updateButtons();
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        allBtn = btn("Marcar todos", v -> toggleAll());
        goBtn = btn("Transcribir", v -> transcribeSelected());
        bottom.addView(allBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bottom.addView(goBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        root.addView(bottom);

        setContentView(root);
        updateButtons();
    }

    // ---------- helpers de UI ----------

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Spinner spinner(String[] items, int sel) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        if (sel < items.length) s.setSelection(sel);
        return s;
    }

    private CheckBox check(String t, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setChecked(v);
        return c;
    }

    private LinearLayout.LayoutParams half() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        searchBtn.setEnabled(!b);
        moreBtn.setEnabled(!b);
        allBtn.setEnabled(!b);
        updateButtons();
    }

    private void showStatus(String s) {
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    private void updateButtons() {
        goBtn.setText(busy && cancelled == false && goBtn.getTag() != null ? "Parar"
                : "Transcribir (" + selected.size() + ")");
        goBtn.setEnabled(goBtn.getTag() != null || (!busy && !selected.isEmpty()));
        boolean allSel = !results.isEmpty() && selected.size() >= results.size();
        allBtn.setText(allSel ? "Desmarcar todos" : "Marcar todos");
    }

    private void savePrefs() {
        prefs.edit()
                .putString("ultima_busqueda", query.getText().toString().trim())
                .putInt("idioma", langSp.getSelectedItemPosition())
                .putInt("orden", sortSp.getSelectedItemPosition())
                .putInt("fecha", dateSp.getSelectedItemPosition())
                .putInt("duracion", durSp.getSelectedItemPosition())
                .putBoolean("solo_subs", subsCheck.isChecked())
                .putBoolean("traducir", translateCheck.isChecked())
                .putBoolean("marcas", tsCheck.isChecked())
                .apply();
    }

    // ---------- Búsqueda ----------

    private void newSearch() {
        String q = query.getText().toString().trim();
        if (q.isEmpty() || busy) return;
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(query.getWindowToken(), 0);
        savePrefs();
        results.clear();
        selected.clear();
        continuation = null;
        adapter.notifyDataSetChanged();
        moreBtn.setVisibility(View.GONE);
        status.setVisibility(View.GONE);

        int li = langSp.getSelectedItemPosition();
        String params = TranscriptFetcher.searchParams(
                SORT_VALS[sortSp.getSelectedItemPosition()],
                DATE_VALS[dateSp.getSelectedItemPosition()],
                DUR_VALS[durSp.getSelectedItemPosition()],
                subsCheck.isChecked());
        runSearch(q, null, params, LANG_CODES[li], LANG_GL[li]);
    }

    private void loadMore() {
        if (continuation == null || busy) return;
        int li = langSp.getSelectedItemPosition();
        runSearch(null, continuation, null, LANG_CODES[li], LANG_GL[li]);
    }

    private void runSearch(String q, String cont, String params, String hl, String gl) {
        setBusy(true);
        exec.execute(() -> {
            try {
                TranscriptFetcher.SearchPage page = TranscriptFetcher.search(q, cont, params, hl, gl);
                ui.post(() -> {
                    for (TranscriptFetcher.SearchResult r : page.results) {
                        boolean dup = false;
                        for (TranscriptFetcher.SearchResult e : results) if (e.videoId.equals(r.videoId)) dup = true;
                        if (!dup) results.add(r);
                    }
                    continuation = page.continuation;
                    moreBtn.setVisibility(continuation != null && !page.results.isEmpty() ? View.VISIBLE : View.GONE);
                    adapter.notifyDataSetChanged();
                    if (results.isEmpty()) showStatus("Sin resultados. Prueba otra búsqueda o quita filtros.");
                    else showStatus(results.size() + " vídeos. Toca para marcar los que quieras.");
                    setBusy(false);
                });
            } catch (Exception e) {
                ui.post(() -> { setBusy(false); showStatus("Error al buscar: " + e.getMessage()); });
            }
        });
    }

    private void toggleAll() {
        boolean allSel = selected.size() >= results.size();
        selected.clear();
        if (!allSel) for (TranscriptFetcher.SearchResult r : results) selected.add(r.videoId);
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    // ---------- Transcripción por lotes ----------

    private void transcribeSelected() {
        if (goBtn.getTag() != null) { // botón en modo "Parar"
            cancelled = true;
            goBtn.setEnabled(false);
            showStatus("Parando tras el vídeo actual…");
            return;
        }
        if (selected.isEmpty() || busy) return;
        savePrefs();
        int li = langSp.getSelectedItemPosition();
        String lang = LANG_CODES[li], langName = LANG_NAMES[li];
        boolean translate = translateCheck.isChecked(), ts = tsCheck.isChecked();
        List<TranscriptFetcher.SearchResult> todo = new ArrayList<>();
        for (TranscriptFetcher.SearchResult r : results) if (selected.contains(r.videoId)) todo.add(r);

        cancelled = false;
        goBtn.setTag("running");
        setBusy(true);
        progress.setIndeterminate(false);
        progress.setMax(todo.size());
        progress.setProgress(0);

        exec.execute(() -> {
            Collection coll = new Collection(this);
            int ok = 0, done = 0;
            StringBuilder fails = new StringBuilder();
            for (TranscriptFetcher.SearchResult r : todo) {
                if (cancelled) break;
                final int n = done + 1;
                ui.post(() -> showStatus("Transcribiendo " + n + " de " + todo.size() + ": " + r.title));
                try {
                    TranscriptFetcher.Result res = TranscriptFetcher.transcribe(r.videoId, lang, langName, ts, translate);
                    coll.put(res.info.id, res.info.title, res.info.author, res.track.languageCode, res.text);
                    ok++;
                    final String vid = r.videoId;
                    ui.post(() -> { selected.remove(vid); adapter.notifyDataSetChanged(); });
                } catch (Exception e) {
                    fails.append("• ").append(r.title).append(": ").append(e.getMessage()).append('\n');
                }
                done++;
                final int d = done;
                ui.post(() -> progress.setProgress(d));
                try { Thread.sleep(600); } catch (InterruptedException ie) { break; } // no saturar a YouTube
            }
            final int okF = ok;
            final int total = coll.size();
            ui.post(() -> {
                goBtn.setTag(null);
                progress.setIndeterminate(true);
                setBusy(false);
                showStatus(okF + " transcripciones añadidas. Colección: " + total + ".");
                AlertDialog.Builder d = new AlertDialog.Builder(this)
                        .setTitle(okF + " añadidas a la colección")
                        .setMessage(fails.length() == 0 ? "Puedes exportarlas a PDF o TXT desde la colección."
                                : "No se pudieron transcribir:\n" + fails.toString().trim())
                        .setPositiveButton("Ver colección", (x, y) -> {
                            setResult(RESULT_OK, getIntent().putExtra(EXTRA_OPEN_COLLECTION, true));
                            finish();
                        })
                        .setNegativeButton("Seguir buscando", null);
                d.show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        exec.shutdownNow();
        super.onDestroy();
    }

    // ---------- Lista ----------

    private class ResultAdapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int i) { return results.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            CheckBox cb;
            TextView t1, t2;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                cb = (CheckBox) row.getChildAt(0);
                LinearLayout txt = (LinearLayout) row.getChildAt(1);
                t1 = (TextView) txt.getChildAt(0);
                t2 = (TextView) txt.getChildAt(1);
            } else {
                row = new LinearLayout(SearchActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                cb = new CheckBox(SearchActivity.this);
                cb.setClickable(false);
                cb.setFocusable(false);
                row.addView(cb);
                LinearLayout txt = new LinearLayout(SearchActivity.this);
                txt.setOrientation(LinearLayout.VERTICAL);
                t1 = new TextView(SearchActivity.this);
                t1.setTextSize(15);
                t1.setTextColor(Color.parseColor("#202124"));
                t1.setMaxLines(2);
                t1.setEllipsize(TextUtils.TruncateAt.END);
                t2 = new TextView(SearchActivity.this);
                t2.setTextSize(12);
                t2.setTextColor(Color.parseColor("#5F6368"));
                t2.setSingleLine(true);
                t2.setEllipsize(TextUtils.TruncateAt.END);
                txt.addView(t1);
                txt.addView(t2);
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            TranscriptFetcher.SearchResult r = results.get(pos);
            cb.setChecked(selected.contains(r.videoId));
            t1.setText(r.title);
            StringBuilder meta = new StringBuilder(r.channel);
            for (String s : new String[]{r.duration, r.published, r.views})
                if (s != null && !s.isEmpty()) meta.append("  ·  ").append(s);
            t2.setText(meta);
            return row;
        }
    }
}
