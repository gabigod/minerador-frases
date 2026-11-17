package com.tem.minerador;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.provider.MediaStore;
import android.content.ContentUris;
import android.database.Cursor;
import android.util.Base64;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Acesso a todos os arquivos: lista os videos, audios e legendas do aparelho
 * e le pedacos de arquivos para o app (o video em si toca pelo endereco local do Capacitor).
 */
@CapacitorPlugin(name = "Arquivos")
public class FilesPlugin extends Plugin {

    private static final String VID = "mp4|mkv|webm|m4v|mov|avi|ts|m2ts|wmv|flv|3gp";
    private static final String AUD = "mp3|m4a|aac|flac|wav|ogg|opus|wma";
    private static final String SUB = "srt|vtt|ass|ssa|smi|sub";

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", hasAccess());
        r.put("sdk", Build.VERSION.SDK_INT);
        call.resolve(r);
    }

    /** abre a tela do Android "Acesso a todos os arquivos" (ou pede a permissao antiga) */
    @PluginMethod
    public void request(PluginCall call) {
        JSObject r = new JSObject();
        if (hasAccess()) {
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    getContext().startActivity(i);
                } catch (Exception e) {
                    Intent j = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(j);
                }
            } else {
                ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, 7301);
            }
            r.put("granted", false);
            r.put("opened", true);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("nao consegui abrir a tela de permissao: " + e.getMessage());
        }
    }

    private Set<String> roots() {
        Set<String> out = new LinkedHashSet<>();
        File main = Environment.getExternalStorageDirectory();
        if (main != null) out.add(main.getAbsolutePath());
        try {
            for (File f : getContext().getExternalFilesDirs(null)) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int k = p.indexOf("/Android/data/");
                if (k > 0) out.add(p.substring(0, k));
            }
        } catch (Exception e) {
            // ignora
        }
        return out;
    }

    private static String ext(String n) {
        int d = n.lastIndexOf('.');
        return d < 0 ? "" : n.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    private void walk(File dir, String root, int depth, JSArray out, int[] count) {
        if (depth > 9 || count[0] >= 12000) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (count[0] >= 12000) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && (n.equals("Android") || n.equals("LOST.DIR"))) continue;
                walk(f, root, depth + 1, out, count);
                continue;
            }
            String e = ext(n);
            String k = e.matches(VID) ? "v" : e.matches(AUD) ? "a" : e.matches(SUB) ? "s" : null;
            if (k == null) continue;
            if (k.equals("a") && f.length() < 200 * 1024) continue; // toques e sons curtos
            JSObject o = new JSObject();
            String p = f.getAbsolutePath();
            o.put("p", p);
            o.put("n", n);
            o.put("s", f.length());
            o.put("m", f.lastModified());
            o.put("k", k);
            o.put("r", p.startsWith(root + "/") ? p.substring(root.length() + 1) : n);
            out.put(o);
            count[0]++;
        }
    }

    /** lista videos, audios (maiores que 200 KB) e legendas do aparelho e do cartao SD */
    @PluginMethod
    public void scan(final PluginCall call) {
        if (!hasAccess()) {
            call.reject("sem permissao");
            return;
        }
        new Thread(() -> {
            try {
                JSArray out = new JSArray();
                int[] count = { 0 };
                for (String r : roots()) walk(new File(r), r, 0, out, count);
                JSObject res = new JSObject();
                res.put("files", out);
                res.put("truncated", count[0] >= 12000);
                call.resolve(res);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        }).start();
    }

    /** endereco content:// do arquivo na galeria do Android (o reprodutor do WebView abre igual a um arquivo escolhido) */
    @PluginMethod
    public void contentUri(final PluginCall call) {
        final String path = call.getString("path");
        new Thread(() -> {
            JSObject r = new JSObject();
            Cursor c = null;
            try {
                Uri base = MediaStore.Files.getContentUri("external");
                c = getContext().getContentResolver().query(base, new String[] { MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE }, MediaStore.Files.FileColumns.DATA + "=?", new String[] { path }, null);
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(0);
                    int mt = c.getInt(1);
                    Uri u = mt == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        : mt == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO ? ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        : ContentUris.withAppendedId(base, id);
                    r.put("uri", u.toString());
                }
            } catch (Exception e) {
                r.put("error", String.valueOf(e.getMessage()));
            } finally {
                if (c != null) c.close();
            }
            call.resolve(r);
        }).start();
    }

    /** le um pedaco de um arquivo (base64), para quando o endereco local nao aceitar leitura por partes */
    @PluginMethod
    public void read(final PluginCall call) {
        final String path = call.getString("path");
        final long off = (long) Math.floor(call.getDouble("off", 0.0));
        final int len = call.getInt("len", 0);
        new Thread(() -> {
            RandomAccessFile f = null;
            try {
                if (path == null || len <= 0 || len > 16 * 1024 * 1024) throw new Exception("pedido invalido");
                f = new RandomAccessFile(path, "r");
                f.seek(off);
                byte[] b = new byte[len];
                int o = 0;
                while (o < len) {
                    int n = f.read(b, o, len - o);
                    if (n < 0) break;
                    o += n;
                }
                JSObject r = new JSObject();
                r.put("data", Base64.encodeToString(b, 0, o, Base64.NO_WRAP));
                call.resolve(r);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            } finally {
                try {
                    if (f != null) f.close();
                } catch (Exception e) {
                    // ignora
                }
            }
        }).start();
    }
}
