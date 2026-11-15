package com.tem.minerador;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.util.Base64;
import android.app.Activity;
import android.content.SharedPreferences;
import android.provider.DocumentsContract;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.annotation.ActivityCallback;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import com.github.luben.zstd.ZstdInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ponte com o AnkiDroid (API oficial por ContentProvider).
 * Le o estado dos cartoes, envia cartoes novos e marca cartoes como esquecidos.
 */
@CapacitorPlugin(
    name = "Anki",
    permissions = { @Permission(alias = "anki", strings = { "com.ichi2.anki.permission.READ_WRITE_DATABASE" }) }
)
public class AnkiPlugin extends Plugin {

    private static final String AUTH = "com.ichi2.anki.flashcards";
    private static final String SEP = "\u001f";

    private Uri uri(String path) {
        return Uri.parse("content://" + AUTH + "/" + path);
    }

    private String ankiPackage() {
        try {
            ProviderInfo info = getContext().getPackageManager().resolveContentProvider(AUTH, 0);
            return info == null ? null : info.packageName;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean granted() {
        return getPermissionState("anki") == PermissionState.GRANTED;
    }

    private JSObject state() {
        JSObject r = new JSObject();
        r.put("installed", ankiPackage() != null);
        r.put("granted", granted());
        return r;
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(state());
    }

    @PluginMethod
    public void request(PluginCall call) {
        if (ankiPackage() == null || granted()) {
            call.resolve(state());
            return;
        }
        requestPermissionForAlias("anki", call, "permDone");
    }

    @PermissionCallback
    private void permDone(PluginCall call) {
        call.resolve(state());
    }

    private interface Job {
        void run() throws Exception;
    }

    private void background(final PluginCall call, final Job job) {
        if (ankiPackage() == null) {
            call.reject("AnkiDroid nao instalado");
            return;
        }
        if (!granted()) {
            call.reject("sem permissao do AnkiDroid");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    job.run();
                } catch (Exception e) {
                    String m = e.getMessage();
                    call.reject(m == null ? e.toString() : m);
                }
            }
        }).start();
    }

    /** Modelos (tipos de nota) e baralhos. */
    @PluginMethod
    public void info(final PluginCall call) {
        background(call, new Job() {
            @Override
            public void run() throws Exception {
                ContentResolver cr = getContext().getContentResolver();
                JSArray models = new JSArray();
                Cursor c = cr.query(uri("models"), null, null, null, null);
                if (c != null) {
                    try {
                        int iId = c.getColumnIndex("_id"), iName = c.getColumnIndex("name"), iF = c.getColumnIndex("field_names");
                        while (c.moveToNext()) {
                            JSObject m = new JSObject();
                            m.put("id", String.valueOf(c.getLong(iId)));
                            m.put("name", c.getString(iName));
                            JSArray fs = new JSArray();
                            String raw = iF >= 0 ? c.getString(iF) : null;
                            if (raw != null) {
                                for (String f : raw.split(SEP, -1)) fs.put(f);
                            }
                            m.put("fields", fs);
                            models.put(m);
                        }
                    } finally {
                        c.close();
                    }
                }
                JSArray decks = new JSArray();
                Cursor d = cr.query(uri("decks"), null, null, null, null);
                if (d != null) {
                    try {
                        int iId = d.getColumnIndex("deck_id"), iName = d.getColumnIndex("deck_name");
                        while (d.moveToNext()) {
                            JSObject o = new JSObject();
                            o.put("id", String.valueOf(d.getLong(iId)));
                            o.put("name", d.getString(iName));
                            decks.put(o);
                        }
                    } finally {
                        d.close();
                    }
                }
                JSObject r = new JSObject();
                r.put("models", models);
                r.put("decks", decks);
                call.resolve(r);
            }
        });
    }

    private JSArray idsFor(ContentResolver cr, String search) {
        JSArray out = new JSArray();
        Cursor c = null;
        try {
            c = cr.query(uri("notes"), new String[] { "_id" }, search, null, null);
            if (c != null) {
                while (c.moveToNext()) out.put(String.valueOf(c.getLong(0)));
            }
        } catch (Exception e) {
            // busca nao suportada nesta versao: devolve vazio
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Palavras alvo de todas as notas que tem o campo pedido, mais quais estao maduras e quais foram erradas. */
    @PluginMethod
    public void words(final PluginCall call) {
        final String field = call.getString("field", "Target Word");
        final String sfield = call.getString("sentField", "Sentence");
        final int matureDays = call.getInt("matureDays", 21);
        final int againDays = call.getInt("againDays", 7);
        background(call, new Job() {
            @Override
            public void run() throws Exception {
                ContentResolver cr = getContext().getContentResolver();
                Map<Long, Integer> idx = new HashMap<Long, Integer>();
                Map<Long, Integer> sidx = new HashMap<Long, Integer>();
                Cursor c = cr.query(uri("models"), null, null, null, null);
                if (c != null) {
                    try {
                        int iId = c.getColumnIndex("_id"), iF = c.getColumnIndex("field_names");
                        while (c.moveToNext()) {
                            String raw = iF >= 0 ? c.getString(iF) : null;
                            if (raw == null) continue;
                            String[] fs = raw.split(SEP, -1);
                            for (int i = 0; i < fs.length; i++) {
                                if (fs[i].trim().equalsIgnoreCase(field.trim())) {
                                    idx.put(c.getLong(iId), i);
                                    break;
                                }
                            }
                            for (int i = 0; i < fs.length; i++) {
                                if (fs[i].trim().equalsIgnoreCase(sfield.trim())) {
                                    sidx.put(c.getLong(iId), i);
                                    break;
                                }
                            }
                        }
                    } finally {
                        c.close();
                    }
                }
                JSArray ids = new JSArray();
                JSArray words = new JSArray();
                JSArray sents = new JSArray();
                for (Map.Entry<Long, Integer> e : idx.entrySet()) {
                    Integer si = sidx.get(e.getKey());
                    Cursor n = cr.query(uri("notes"), new String[] { "_id", "flds" }, "mid:" + e.getKey(), null, null);
                    if (n == null) continue;
                    try {
                        while (n.moveToNext()) {
                            String flds = n.getString(1);
                            if (flds == null) continue;
                            String[] parts = flds.split(SEP, -1);
                            int i = e.getValue();
                            if (i >= parts.length) continue;
                            String w = parts[i];
                            if (w == null || w.trim().isEmpty()) continue;
                            if (w.length() > 200) w = w.substring(0, 200);
                            String sv = (si != null && si < parts.length && parts[si] != null) ? parts[si] : "";
                            if (sv.length() > 400) sv = sv.substring(0, 400);
                            ids.put(String.valueOf(n.getLong(0)));
                            words.put(w);
                            sents.put(sv);
                        }
                    } finally {
                        n.close();
                    }
                }
                JSObject r = new JSObject();
                r.put("ids", ids);
                r.put("words", words);
                r.put("sents", sents);
                r.put("mature", idsFor(cr, "prop:ivl>=" + matureDays));
                r.put("again", idsFor(cr, "rated:" + againDays + ":1"));
                r.put("models", idx.size());
                call.resolve(r);
            }
        });
    }

    private List<Integer> cardOrds(ContentResolver cr, long noteId) {
        List<Integer> ords = new ArrayList<Integer>();
        Cursor c = null;
        try {
            c = cr.query(uri("notes/" + noteId + "/cards"), new String[] { "ord" }, null, null, null);
            if (c != null) {
                while (c.moveToNext()) ords.add(c.getInt(0));
            }
        } catch (Exception e) {
            // ignora
        } finally {
            if (c != null) c.close();
        }
        return ords;
    }

    private boolean matches(ContentResolver cr, String search) {
        Cursor c = null;
        try {
            c = cr.query(uri("notes"), new String[] { "_id" }, search, null, null);
            return c != null && c.getCount() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Marca os cartoes das notas como "De novo" (errado). Se nao der, poe a etiqueta "esqueci". */
    @PluginMethod
    public void forget(final PluginCall call) {
        final JSArray arr = call.getArray("ids", new JSArray());
        final String tag = call.getString("tag", "esqueci");
        background(call, new Job() {
            @Override
            public void run() throws Exception {
                ContentResolver cr = getContext().getContentResolver();
                int answered = 0, tagged = 0;
                for (int k = 0; k < arr.length(); k++) {
                    long id;
                    try {
                        id = Long.parseLong(String.valueOf(arr.get(k)));
                    } catch (Exception e) {
                        continue;
                    }
                    boolean ok = false;
                    try {
                        for (int ord : cardOrds(cr, id)) {
                            ContentValues v = new ContentValues();
                            v.put("note_id", id);
                            v.put("ord", ord);
                            v.put("answer_ease", 1);
                            v.put("time_taken", 6000L);
                            cr.update(uri("schedule"), v, null, null);
                        }
                        ok = matches(cr, "nid:" + id + " rated:1:1");
                    } catch (Exception e) {
                        ok = false;
                    }
                    if (ok) {
                        answered++;
                        continue;
                    }
                    try {
                        String tags = "";
                        Cursor c = cr.query(uri("notes/" + id), new String[] { "tags" }, null, null, null);
                        if (c != null) {
                            try {
                                if (c.moveToFirst() && c.getString(0) != null) tags = c.getString(0).trim();
                            } finally {
                                c.close();
                            }
                        }
                        if (!(" " + tags + " ").contains(" " + tag + " ")) {
                            ContentValues v = new ContentValues();
                            v.put("tags", (tags + " " + tag).trim());
                            if (cr.update(uri("notes/" + id), v, null, null) > 0) tagged++;
                        } else {
                            tagged++;
                        }
                    } catch (Exception e) {
                        // ignora esta nota
                    }
                }
                JSObject r = new JSObject();
                r.put("answered", answered);
                r.put("tagged", tagged);
                call.resolve(r);
            }
        });
    }

    /** Apaga a nota do AnkiDroid. Se o AnkiDroid nao deixar, poe a etiqueta "apagar" para apagar no Anki. */
    @PluginMethod
    public void deleteNote(final PluginCall call) {
        final String noteId = call.getString("noteId");
        background(call, new Job() {
            @Override
            public void run() throws Exception {
                if (noteId == null) throw new Exception("cartao nao informado");
                long id = Long.parseLong(noteId);
                ContentResolver cr = getContext().getContentResolver();
                String err = "";
                int n = 0;
                try {
                    n = cr.delete(uri("notes/" + id), null, null);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                boolean gone = false;
                try {
                    Cursor c = cr.query(uri("notes/" + id), new String[] { "_id" }, null, null, null);
                    if (c == null) gone = true;
                    else {
                        try {
                            gone = !c.moveToFirst();
                        } finally {
                            c.close();
                        }
                    }
                } catch (Exception e) {
                    gone = n > 0;
                }
                boolean tagged = false;
                if (!gone) {
                    try {
                        String tags = "";
                        Cursor c = cr.query(uri("notes/" + id), new String[] { "tags" }, null, null, null);
                        if (c != null) {
                            try {
                                if (c.moveToFirst() && c.getString(0) != null) tags = c.getString(0).trim();
                            } finally {
                                c.close();
                            }
                        }
                        if (!(" " + tags + " ").contains(" apagar ")) {
                            ContentValues v = new ContentValues();
                            v.put("tags", (tags + " apagar").trim());
                            tagged = cr.update(uri("notes/" + id), v, null, null) > 0;
                        } else tagged = true;
                    } catch (Exception e) {
                        if (err.isEmpty()) err = String.valueOf(e.getMessage());
                    }
                }
                JSObject r = new JSObject();
                r.put("deleted", gone);
                r.put("tagged", tagged);
                r.put("error", err);
                call.resolve(r);
            }
        });
    }

    /* ---------- midia dos cartoes (audio e imagem) pela pasta do AnkiDroid liberada uma vez ---------- */
    private Map<String, String> mediaIdx = null;
    private String mediaDir = null;

    private SharedPreferences mp() {
        return getContext().getSharedPreferences("anki-media", Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void mediaPick(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:AnkiDroid"));
        } catch (Exception e) {
            // ignora
        }
        startActivityForResult(call, i, "mediaPicked");
    }

    @ActivityCallback
    private void mediaPicked(PluginCall call, ActivityResult res) {
        if (call == null) return;
        Intent d = res.getData();
        if (res.getResultCode() != Activity.RESULT_OK || d == null || d.getData() == null) {
            call.reject("nenhuma pasta escolhida");
            return;
        }
        Uri tree = d.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // segue mesmo sem permissao permanente
        }
        mp().edit().putString("tree", tree.toString()).apply();
        mediaIdx = null;
        mediaDir = null;
        final PluginCall c = call;
        background(c, new Job() {
            @Override
            public void run() throws Exception {
                int n = indexMedia();
                JSObject r = new JSObject();
                r.put("files", n);
                r.put("ok", n > 0);
                c.resolve(r);
            }
        });
    }

    private Cursor children(Uri tree, String docId) {
        Uri ch = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        return getContext().getContentResolver().query(ch, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null);
    }

    /** acha a pasta collection.media (na pasta escolhida ou dentro dela) e lista os arquivos */
    private synchronized int indexMedia() throws Exception {
        String t = mp().getString("tree", null);
        if (t == null) return 0;
        Uri tree = Uri.parse(t);
        String root = DocumentsContract.getTreeDocumentId(tree);
        String dir = null;
        if (root.endsWith("collection.media")) dir = root;
        if (dir == null) {
            Cursor c = children(tree, root);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        if ("collection.media".equals(c.getString(1))) {
                            dir = c.getString(0);
                            break;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        if (dir == null) dir = root;
        Map<String, String> idx = new HashMap<>();
        Cursor c = children(tree, dir);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    String mime = c.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                    idx.put(c.getString(1), c.getString(0));
                }
            } finally {
                c.close();
            }
        }
        mediaDir = dir;
        mediaIdx = idx;
        return idx.size();
    }

    @PluginMethod
    public void mediaInfo(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", mp().getString("tree", null) != null || impCount() > 0);
        r.put("imported", impCount());
        call.resolve(r);
    }

    /* ---------- midia importada de um .apkg exportado pelo AnkiDroid (para quando a pasta fica em Android/data) ---------- */
    private File impDir() {
        File d = new File(getContext().getFilesDir(), "ankimedia");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private int impCount() {
        String[] l = impDir().list();
        return l == null ? 0 : l.length;
    }

    private static String safeName(String n) {
        return n.replace("/", "_").replace("\\", "_");
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
            return os.toByteArray();
        } finally {
            in.close();
        }
    }

    private static long rdVarint(byte[] b, int[] p) {
        long v = 0;
        int sh = 0;
        while (p[0] < b.length) {
            int x = b[p[0]++] & 0xFF;
            v |= (long) (x & 0x7F) << sh;
            if ((x & 0x80) == 0) break;
            sh += 7;
        }
        return v;
    }

    /** lista de nomes da midia: JSON (formato antigo) ou protobuf (formato novo do Anki) */
    private static Map<String, String> mediaMap(byte[] b) throws Exception {
        Map<String, String> m = new HashMap<>();
        int i = 0;
        while (i < b.length && (b[i] == ' ' || b[i] == '\n' || b[i] == '\r' || b[i] == '\t')) i++;
        if (i < b.length && b[i] == '{') {
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, o.getString(k));
            }
            return m;
        }
        int[] p = { 0 };
        int idx = 0;
        while (p[0] < b.length) {
            long tag = rdVarint(b, p);
            int field = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 2) {
                int len = (int) rdVarint(b, p);
                int end = p[0] + len;
                if (field == 1) {
                    String name = null;
                    int[] q = { p[0] };
                    while (q[0] < end) {
                        long t2 = rdVarint(b, q);
                        int f2 = (int) (t2 >>> 3), w2 = (int) (t2 & 7);
                        if (w2 == 2) {
                            int l2 = (int) rdVarint(b, q);
                            if (f2 == 1) name = new String(b, q[0], l2, "UTF-8");
                            q[0] += l2;
                        } else if (w2 == 0) rdVarint(b, q);
                        else if (w2 == 5) q[0] += 4;
                        else if (w2 == 1) q[0] += 8;
                        else break;
                    }
                    if (name != null) m.put(String.valueOf(idx), name);
                    idx++;
                }
                p[0] = end;
            } else if (wt == 0) rdVarint(b, p);
            else if (wt == 5) p[0] += 4;
            else if (wt == 1) p[0] += 8;
            else break;
        }
        return m;
    }

    private static boolean isZstd(byte[] h, int n) {
        return n >= 4 && (h[0] & 0xFF) == 0x28 && (h[1] & 0xFF) == 0xB5 && (h[2] & 0xFF) == 0x2F && (h[3] & 0xFF) == 0xFD;
    }

    /** escolhe o .apkg/.colpkg exportado e copia a midia para dentro do app */
    @PluginMethod
    public void mediaImport(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(call, i, "mediaImported");
    }

    @ActivityCallback
    private void mediaImported(PluginCall call, ActivityResult res) {
        if (call == null) return;
        Intent d = res.getData();
        if (res.getResultCode() != Activity.RESULT_OK || d == null || d.getData() == null) {
            call.reject("nenhum arquivo escolhido");
            return;
        }
        final Uri u = d.getData();
        final PluginCall c = call;
        background(c, new Job() {
            @Override
            public void run() throws Exception {
                File dir = impDir(), tmp = new File(getContext().getCacheDir(), "apkgtmp");
                if (tmp.exists()) for (File f : tmp.listFiles()) f.delete();
                tmp.mkdirs();
                byte[] mapBytes = null;
                int n = 0;
                InputStream in0 = getContext().getContentResolver().openInputStream(u);
                if (in0 == null) throw new Exception("nao consegui abrir o arquivo");
                ZipInputStream z = new ZipInputStream(new BufferedInputStream(in0, 1 << 16));
                byte[] buf = new byte[1 << 16];
                try {
                    ZipEntry e;
                    while ((e = z.getNextEntry()) != null) {
                        String nm = e.getName();
                        if (nm.equals("media")) {
                            ByteArrayOutputStream os = new ByteArrayOutputStream();
                            int k;
                            while ((k = z.read(buf)) > 0) os.write(buf, 0, k);
                            byte[] raw = os.toByteArray();
                            if (isZstd(raw, raw.length)) {
                                ZstdInputStream zi = new ZstdInputStream(new java.io.ByteArrayInputStream(raw));
                                ByteArrayOutputStream o2 = new ByteArrayOutputStream();
                                while ((k = zi.read(buf)) > 0) o2.write(buf, 0, k);
                                zi.close();
                                raw = o2.toByteArray();
                            }
                            mapBytes = raw;
                        } else if (nm.matches("\\d+")) {
                            File out = new File(tmp, nm);
                            BufferedInputStream bi = new BufferedInputStream(z, 1 << 16);
                            bi.mark(8);
                            byte[] h = new byte[4];
                            int hn = bi.read(h, 0, 4);
                            bi.reset();
                            InputStream keep = new java.io.FilterInputStream(bi) {
                                @Override
                                public void close() {
                                    // nao fecha o zip
                                }
                            };
                            boolean zs = isZstd(h, hn);
                            InputStream src = zs ? new ZstdInputStream(keep) : keep;
                            OutputStream os = new FileOutputStream(out);
                            try {
                                int k;
                                while ((k = src.read(buf)) > 0) os.write(buf, 0, k);
                            } finally {
                                os.close();
                                if (zs) src.close();
                            }
                            n++;
                            if (n % 200 == 0) {
                                JSObject pr = new JSObject();
                                pr.put("n", n);
                                notifyListeners("mediaProgress", pr);
                            }
                        }
                        z.closeEntry();
                    }
                } finally {
                    try {
                        z.close();
                    } catch (Exception ignored) {
                        // ignora
                    }
                }
                if (mapBytes == null) throw new Exception("esse arquivo nao tem a midia (marque “Incluir midia” ao exportar)");
                Map<String, String> m = mediaMap(mapBytes);
                int ok = 0;
                for (Map.Entry<String, String> en : m.entrySet()) {
                    File f = new File(tmp, en.getKey());
                    if (!f.isFile()) continue;
                    File dst = new File(dir, safeName(en.getValue()));
                    if (dst.exists()) dst.delete();
                    if (!f.renameTo(dst)) {
                        FileInputStream fi = new FileInputStream(f);
                        FileOutputStream fo = new FileOutputStream(dst);
                        int k;
                        while ((k = fi.read(buf)) > 0) fo.write(buf, 0, k);
                        fi.close();
                        fo.close();
                        f.delete();
                    }
                    ok++;
                }
                for (File f : tmp.listFiles()) f.delete();
                JSObject r = new JSObject();
                r.put("files", ok);
                r.put("total", impCount());
                c.resolve(r);
            }
        });
    }

    /** devolve um arquivo de midia do Anki em base64 */
    @PluginMethod
    public void media(final PluginCall call) {
        final String name = call.getString("name");
        background(call, new Job() {
            @Override
            public void run() throws Exception {
                JSObject r = new JSObject();
                File own = name == null ? null : new File(impDir(), safeName(name));
                if (own != null && own.isFile()) {
                    r.put("data", Base64.encodeToString(readAll(own), Base64.NO_WRAP));
                    call.resolve(r);
                    return;
                }
                String t = mp().getString("tree", null);
                if (t == null || name == null) {
                    int have = impCount();
                    r.put("need", have == 0);
                    if (have > 0) r.put("missing", true);
                    call.resolve(r);
                    return;
                }
                if (mediaIdx == null) indexMedia();
                String id = mediaIdx.get(name);
                if (id == null) {
                    indexMedia();
                    id = mediaIdx.get(name);
                }
                if (id == null) {
                    r.put("missing", true);
                    r.put("files", mediaIdx.size());
                    call.resolve(r);
                    return;
                }
                Uri u = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(t), id);
                InputStream in = getContext().getContentResolver().openInputStream(u);
                if (in == null) throw new Exception("nao consegui abrir " + name);
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n, tot = 0;
                try {
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        tot += n;
                        if (tot > 12 * 1024 * 1024) throw new Exception("arquivo grande demais");
                    }
                } finally {
                    in.close();
                }
                r.put("data", Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP));
                call.resolve(r);
            }
        });
    }

    private Map<Long, String[]> modelFields(ContentResolver cr, Map<Long, String> names) {
        Map<Long, String[]> out = new HashMap<Long, String[]>();
        Cursor c = cr.query(uri("models"), null, null, null, null);
        if (c != null) {
            try {
                int iId = c.getColumnIndex("_id"), iName = c.getColumnIndex("name"), iF = c.getColumnIndex("field_names");
                while (c.moveToNext()) {
                    String raw = iF >= 0 ? c.getString(iF) : null;
                    out.put(c.getLong(iId), raw == null ? new String[0] : raw.split(SEP, -1));
                    if (iName >= 0) names.put(c.getLong(iId), c.getString(iName));
                }
            } finally {
                c.close();
            }
        }
        return out;
    }

    private int col(Cursor c, String a, String b) {
        int i = c.getColumnIndex(a);
        return i >= 0 ? i : c.getColumnIndex(b);
    }

    /**
     * Cartoes que o AnkiDroid manda revisar agora (mesma ordem e limites do Anki).
     * deck = nome do baralho (opcional), limit = quantos pegar.
     */
    @PluginMethod
    public void due(final PluginCall call) {
        final String deck = call.getString("deck", "");
        final int limit = call.getInt("limit", 50);
        background(call, new Job() {
            @Override
            public void run() throws Exception {
                ContentResolver cr = getContext().getContentResolver();
                String sel = "limit=?";
                String[] args;
                if (deck != null && !deck.trim().isEmpty()) {
                    sel = "limit=?,deckID=?";
                    args = new String[] { String.valueOf(limit), String.valueOf(deckId(cr, deck)) };
                } else {
                    args = new String[] { String.valueOf(limit) };
                }
                Map<Long, String> mnames = new HashMap<Long, String>();
                Map<Long, String[]> mf = modelFields(cr, mnames);
                JSArray out = new JSArray();
                Cursor c = cr.query(uri("schedule"), null, sel, args, null);
                if (c != null) {
                    try {
                        int iNote = col(c, "note_id", "noteId"), iOrd = col(c, "ord", "card_ord"), iBtn = c.getColumnIndex("button_count"), iNext = c.getColumnIndex("next_review_times");
                        while (c.moveToNext()) {
                            long nid = c.getLong(iNote);
                            JSObject o = new JSObject();
                            o.put("noteId", String.valueOf(nid));
                            o.put("ord", iOrd >= 0 ? c.getInt(iOrd) : 0);
                            o.put("buttons", iBtn >= 0 ? c.getInt(iBtn) : 4);
                            JSArray times = new JSArray();
                            if (iNext >= 0 && c.getString(iNext) != null) {
                                String raw = c.getString(iNext);
                                try {
                                    org.json.JSONArray ja = new org.json.JSONArray(raw);
                                    for (int k = 0; k < ja.length(); k++) times.put(String.valueOf(ja.get(k)));
                                } catch (Exception e) {
                                    for (String t : raw.replace("[", "").replace("]", "").replace("\"", "").split(",")) times.put(t.trim());
                                }
                            }
                            o.put("times", times);
                            Cursor n = cr.query(uri("notes/" + nid), new String[] { "mid", "flds", "tags" }, null, null, null);
                            if (n != null) {
                                try {
                                    if (n.moveToFirst()) {
                                        long mid = n.getLong(0);
                                        String[] names = mf.get(mid);
                                        String[] vals = n.getString(1) == null ? new String[0] : n.getString(1).split(SEP, -1);
                                        JSObject f = new JSObject();
                                        for (int k = 0; k < vals.length; k++) f.put(names != null && k < names.length ? names[k] : ("f" + k), vals[k]);
                                        o.put("fields", f);
                                        o.put("model", mnames.get(mid));
                                    }
                                } finally {
                                    n.close();
                                }
                            }
                            out.put(o);
                        }
                    } finally {
                        c.close();
                    }
                }
                JSObject r = new JSObject();
                r.put("cards", out);
                call.resolve(r);
            }
        });
    }

    /** Responde um cartao no AnkiDroid. ease: 1 = De novo, 2..4 = os outros botoes do Anki. */
    @PluginMethod
    public void answer(final PluginCall call) {
        final String noteId = call.getString("noteId");
        final int ord = call.getInt("ord", 0);
        final int ease = call.getInt("ease", 3);
        final long ms = call.getInt("ms", 8000);
        background(call, new Job() {
            @Override
            public void run() throws Exception {
                if (noteId == null) throw new Exception("cartao nao informado");
                ContentResolver cr = getContext().getContentResolver();
                ContentValues v = new ContentValues();
                v.put("note_id", Long.parseLong(noteId));
                v.put("ord", ord);
                v.put("answer_ease", ease);
                v.put("time_taken", ms);
                int n = cr.update(uri("schedule"), v, null, null);
                JSObject r = new JSObject();
                r.put("updated", n);
                r.put("verified", matches(cr, "nid:" + noteId + " rated:1:" + ease));
                call.resolve(r);
            }
        });
    }

    private long deckId(ContentResolver cr, String name) throws Exception {
        Cursor d = cr.query(uri("decks"), null, null, null, null);
        if (d != null) {
            try {
                int iId = d.getColumnIndex("deck_id"), iName = d.getColumnIndex("deck_name");
                while (d.moveToNext()) {
                    String n = d.getString(iName);
                    if (n != null && n.equalsIgnoreCase(name)) return d.getLong(iId);
                }
            } finally {
                d.close();
            }
        }
        ContentValues v = new ContentValues();
        v.put("deck_name", name);
        Uri u = cr.insert(uri("decks"), v);
        if (u == null) throw new Exception("nao consegui criar o baralho");
        return Long.parseLong(u.getLastPathSegment());
    }

    private String addMedia(ContentResolver cr, String pkg, String name, String b64) throws Exception {
        Context ctx = getContext();
        File dir = new File(ctx.getCacheDir(), "anki-envio");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("sem pasta temporaria");
        File f = new File(dir, name);
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(Base64.decode(b64, Base64.DEFAULT));
        } finally {
            os.close();
        }
        try {
            Uri fu = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
            ctx.grantUriPermission(pkg, fu, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String pref = name;
            int dot = pref.lastIndexOf('.');
            if (dot > 0) pref = pref.substring(0, dot);
            ContentValues v = new ContentValues();
            v.put("file_uri", fu.toString());
            v.put("preferred_name", pref.replace(" ", "_"));
            Uri ret = cr.insert(uri("media"), v);
            if (ret == null || ret.getPath() == null) throw new Exception("o AnkiDroid nao aceitou a midia");
            String stored = new File(ret.getPath()).getName();
            return stored;
        } finally {
            f.delete();
        }
    }

    /** Cria uma nota no AnkiDroid. Os campos podem ter marcadores %%M0%%, %%M1%%... trocados pelo nome da midia. */
    @PluginMethod
    public void addNote(final PluginCall call) {
        final String modelId = call.getString("modelId");
        final String deckName = call.getString("deck", "Minerador");
        final JSArray fields = call.getArray("fields", new JSArray());
        final JSArray media = call.getArray("media", new JSArray());
        final String tags = call.getString("tags", "minerador");
        background(call, new Job() {
            @Override
            public void run() throws Exception {
                if (modelId == null) throw new Exception("modelo nao informado");
                ContentResolver cr = getContext().getContentResolver();
                String pkg = ankiPackage();
                long did = deckId(cr, deckName);
                String[] names = new String[media.length()];
                for (int i = 0; i < media.length(); i++) {
                    JSObject m = JSObject.fromJSONObject(media.getJSONObject(i));
                    names[i] = addMedia(cr, pkg, m.getString("name"), m.getString("data"));
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < fields.length(); i++) {
                    String f = fields.isNull(i) ? "" : String.valueOf(fields.get(i));
                    for (int k = 0; k < names.length; k++) f = f.replace("%%M" + k + "%%", names[k]);
                    if (i > 0) sb.append(SEP);
                    sb.append(f);
                }
                ContentValues v = new ContentValues();
                v.put("mid", Long.parseLong(modelId));
                v.put("flds", sb.toString());
                v.put("tags", tags);
                Uri nu = cr.insert(uri("notes"), v);
                if (nu == null) throw new Exception("o AnkiDroid nao criou a nota");
                long nid = Long.parseLong(nu.getLastPathSegment());
                for (int ord : cardOrds(cr, nid)) {
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put("deck_id", did);
                        cr.update(uri("notes/" + nid + "/cards/" + ord), cv, null, null);
                    } catch (Exception e) {
                        // o cartao fica no baralho padrao do modelo
                    }
                }
                JSObject r = new JSObject();
                r.put("noteId", String.valueOf(nid));
                call.resolve(r);
            }
        });
    }
}
