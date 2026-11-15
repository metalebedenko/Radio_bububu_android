package ru.bububu.radio;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

/** One inset owner per window. Old decor fitting and modern edge-to-edge are not combined. */
final class PhoneInsets {
    private PhoneInsets() {}
    static void prepare(Activity activity){
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=30)activity.getWindow().setDecorFitsSystemWindows(false);
    }
    static void install(View shell){
        if(Build.VERSION.SDK_INT>=30)shell.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom);return WindowInsets.CONSUMED;
        });
        // API26-29: the default fitting Decor owns bars/cutout; adjustResize owns IME. No second padding.
        shell.requestApplyInsets();
    }
    static void prepare(Dialog dialog){if(dialog.getWindow()!=null)dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
    static int availableHeight(Activity activity){
        android.view.ViewGroup content=activity.findViewById(android.R.id.content);View shell=content!=null&&content.getChildCount()>0?content.getChildAt(0):content;
        if(shell!=null&&shell.getHeight()>0)return Math.max(1,shell.getHeight()-shell.getPaddingTop()-shell.getPaddingBottom());
        android.graphics.Rect visible=new android.graphics.Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);return Math.max(1,visible.height());
    }
}
