package com.personal.yttranscript;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Exporta una o varias transcripciones (con su título) a TXT o PDF. */
public class Exporter {

    public static String toTxt(List<Collection.Item> items) {
        StringBuilder sb = new StringBuilder();
        if (items.size() > 1) {
            sb.append("ÍNDICE\n");
            for (int i = 0; i < items.size(); i++)
                sb.append(i + 1).append(". ").append(items.get(i).title).append('\n');
            sb.append("\n\n");
        }
        for (int i = 0; i < items.size(); i++) {
            Collection.Item it = items.get(i);
            String head = (items.size() > 1 ? (i + 1) + ". " : "") + it.title;
            sb.append("==================================================\n")
              .append(head).append('\n');
            if (!it.author.isEmpty()) sb.append("Canal: ").append(it.author).append('\n');
            sb.append(it.url()).append('\n')
              .append("==================================================\n\n")
              .append(it.text).append("\n\n\n");
        }
        return sb.toString().trim() + "\n";
    }

    public static void writeTxt(List<Collection.Item> items, OutputStream os) throws Exception {
        os.write(toTxt(items).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- PDF (A4, en puntos) ----------

    private static final int W = 595, H = 842, M = 50, CW = W - 2 * M;

    public static void writePdf(List<Collection.Item> items, OutputStream os) throws Exception {
        PdfDocument doc = new PdfDocument();
        Pager pg = new Pager(doc);

        TextPaint titleP = paint(18, true, "#111111");
        TextPaint metaP = paint(10, false, "#666666");
        TextPaint bodyP = paint(11, false, "#222222");
        TextPaint headP = paint(22, true, "#111111");

        if (items.size() > 1) {
            pg.draw(layout("Transcripciones", headP), 16);
            StringBuilder idx = new StringBuilder();
            for (int i = 0; i < items.size(); i++)
                idx.append(i + 1).append(". ").append(items.get(i).title).append('\n');
            pg.draw(layout(idx.toString().trim(), bodyP), 0);
        }

        for (int i = 0; i < items.size(); i++) {
            Collection.Item it = items.get(i);
            pg.newPage();
            String head = (items.size() > 1 ? (i + 1) + ". " : "") + it.title;
            pg.draw(layout(head, titleP), 4);
            String meta = (it.author.isEmpty() ? "" : it.author + "  ·  ") + it.url();
            pg.draw(layout(meta, metaP), 14);
            pg.draw(layout(it.text, bodyP), 0);
        }
        pg.finish();
        doc.writeTo(os);
        doc.close();
    }

    private static TextPaint paint(float size, boolean bold, String color) {
        TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(Color.parseColor(color));
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    private static StaticLayout layout(String text, TextPaint p) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, CW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.25f)
                .build();
    }

    /** Dibuja layouts línea a línea, saltando de página cuando no caben. */
    private static class Pager {
        final PdfDocument doc;
        PdfDocument.Page page;
        int num = 0;
        float y;

        Pager(PdfDocument d) { doc = d; }

        void newPage() {
            if (page != null) doc.finishPage(page);
            num++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, num).create());
            y = M;
            TextPaint np = paint(8, false, "#999999");
            page.getCanvas().drawText(String.valueOf(num), W / 2f, H - 25, np);
        }

        void draw(StaticLayout l, float spaceAfter) {
            if (page == null) newPage();
            for (int i = 0; i < l.getLineCount(); i++) {
                int top = l.getLineTop(i), bottom = l.getLineBottom(i);
                if (y + (bottom - top) > H - M) newPage();
                Canvas c = page.getCanvas();
                c.save();
                c.translate(M, y - top);
                c.clipRect(0, top, CW, bottom);
                l.draw(c);
                c.restore();
                y += bottom - top;
            }
            y += spaceAfter;
        }

        void finish() { if (page != null) doc.finishPage(page); }
    }
}
