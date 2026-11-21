package com.tem.minerador;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.WindowInsets;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Trava a tela na vertical ou na horizontal (ou volta ao automatico),
 * sem depender da rotacao automatica do aparelho.
 */
@CapacitorPlugin(name = "Orient")
public class OrientPlugin extends Plugin {

    @PluginMethod
    public void set(PluginCall call) {
        final String mode = call.getString("mode", "auto");
        final Activity a = getActivity();
        if (a == null) {
            call.reject("sem atividade");
            return;
        }
        a.runOnUiThread(() -> {
            int o;
            if ("portrait".equals(mode)) {
                o = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            } else if ("landscape".equals(mode)) {
                o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            } else {
                o = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            a.setRequestedOrientation(o);
            call.resolve();
        });
    }
    /** Onde fica a camera (entalhe) da tela, em pixels CSS (dp): topo, esquerda, direita. */
    @PluginMethod
    public void cutout(PluginCall call) {
        final Activity a = getActivity();
        if (a == null) {
            call.reject("sem atividade");
            return;
        }
        a.runOnUiThread(() -> {
            int t = 0;
            int l = 0;
            int r = 0;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    WindowInsets wi = a.getWindow().getDecorView().getRootWindowInsets();
                    if (wi != null) {
                        DisplayCutout dc = wi.getDisplayCutout();
                        if (dc != null) {
                            t = dc.getSafeInsetTop();
                            l = dc.getSafeInsetLeft();
                            r = dc.getSafeInsetRight();
                        }
                    }
                }
            } catch (Exception e) {
                t = 0;
                l = 0;
                r = 0;
            }
            float d = a.getResources().getDisplayMetrics().density;
            if (d <= 0) {
                d = 1f;
            }
            JSObject o = new JSObject();
            o.put("top", Math.round(t / d));
            o.put("left", Math.round(l / d));
            o.put("right", Math.round(r / d));
            call.resolve(o);
        });
    }
}
