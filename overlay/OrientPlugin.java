package com.tem.minerador;

import android.app.Activity;
import android.content.pm.ActivityInfo;

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
}
