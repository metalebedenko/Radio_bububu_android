package ru.bububu.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import ru.bububu.radio.sources.RequestScope;
import static org.junit.Assert.*;

/** Narrow native-only actions for dialog rows and acknowledged real Back transitions. */
final class NativeDialogActions {
    private NativeDialogActions() {}

    static void clickListRow(Instrumentation instrumentation,String caption) {
        long end=SystemClock.elapsedRealtime()+8000;
        while(SystemClock.elapsedRealtime()<end) {
            instrumentation.waitForIdleSync();AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();boolean clicked=false;
            if(root!=null)try{clicked=clickRow(root,caption,false);}finally{root.recycle();}
            if(clicked){instrumentation.waitForIdleSync();return;}SystemClock.sleep(50);
        }
        fail("Visible enabled exact ListView row missing: "+caption+"; "+windowState(instrumentation,null,null));
    }
    private static boolean clickRow(AccessibilityNodeInfo node,String caption,boolean insideList) {
        boolean list="android.widget.ListView".equals(String.valueOf(node.getClassName()));
        if(insideList&&!list&&node.isVisibleToUser()&&node.isEnabled()&&node.isClickable()&&containsText(node,caption))
            if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;try{if(clickRow(child,caption,insideList||list))return true;}finally{child.recycle();}}
        return false;
    }
    private static boolean containsText(AccessibilityNodeInfo node,String caption) {
        if(node.isVisibleToUser()&&normalized(caption).equals(normalized(node.getText())))return true;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;try{if(containsText(child,caption))return true;}finally{child.recycle();}}
        return false;
    }

    static void backUntilDismissed(Instrumentation instrumentation,Activity activity,AlertDialog dialog) {
        for(int attempt=0;attempt<3&&main(instrumentation,dialog::isShowing);attempt++) {
            audit("before-back",instrumentation,activity,dialog);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            long end=SystemClock.elapsedRealtime()+1200;
            while(SystemClock.elapsedRealtime()<end&&main(instrumentation,dialog::isShowing)){instrumentation.waitForIdleSync();SystemClock.sleep(50);}
        }
        audit("after-back",instrumentation,activity,dialog);
        assertFalse("Real Back did not dismiss original dialog; "+windowState(instrumentation,activity,dialog),main(instrumentation,dialog::isShowing));
        assertFalse("Back left the app Activity",main(instrumentation,activity::isFinishing));
    }
    static void awaitCancelledAfterDismiss(Instrumentation instrumentation,Activity activity,AlertDialog dialog,RequestScope scope) {
        assertFalse("Cancellation may only be checked after proven dismiss",main(instrumentation,dialog::isShowing));
        long end=SystemClock.elapsedRealtime()+2000;
        while(!scope.isCancelled()&&SystemClock.elapsedRealtime()<end){instrumentation.waitForIdleSync();SystemClock.sleep(50);}
        System.out.println("NativeDialogActions cancelled="+scope.isCancelled()+"; "+windowState(instrumentation,activity,dialog));
        assertTrue("PRODUCTION: proven dialog dismiss did not cancel pending preview; "+windowState(instrumentation,activity,dialog),scope.isCancelled());
    }
    static void backToMainTab(Instrumentation instrumentation,Activity activity,String caption) {
        for(int attempt=0;attempt<3&&!activeButton(instrumentation,caption);attempt++) {
            audit("before-main-back",instrumentation,activity,null);instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            long end=SystemClock.elapsedRealtime()+1200;
            while(SystemClock.elapsedRealtime()<end&&!activeButton(instrumentation,caption)){instrumentation.waitForIdleSync();SystemClock.sleep(50);}
        }
        audit("after-main-back",instrumentation,activity,null);
        assertFalse("Back left the app instead of returning to its navigation",main(instrumentation,activity::isFinishing));
        assertTrue("Main navigation is still covered; "+windowState(instrumentation,activity,null),activeButton(instrumentation,caption));
    }
    private static boolean activeButton(Instrumentation instrumentation,String caption) {
        instrumentation.waitForIdleSync();AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;
        try{return button(root,caption);}finally{root.recycle();}
    }
    private static boolean button(AccessibilityNodeInfo node,String caption) {
        if("android.widget.Button".equals(String.valueOf(node.getClassName()))&&node.isVisibleToUser()&&node.isEnabled()&&node.isClickable()&&normalized(caption).equals(normalized(node.getText())))return true;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;try{if(button(child,caption))return true;}finally{child.recycle();}}
        return false;
    }
    private static void audit(String action,Instrumentation instrumentation,Activity activity,AlertDialog dialog) {
        System.out.println("NativeDialogActions "+action+"; "+windowState(instrumentation,activity,dialog));
    }
    private static String windowState(Instrumentation instrumentation,Activity activity,AlertDialog dialog) {
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();String window="active=null";
        if(root!=null)try{window="windowId="+root.getWindowId()+", rootClass="+root.getClassName();}finally{root.recycle();}
        final String active=window;
        return main(instrumentation,()->{
            View decor=dialog!=null&&dialog.getWindow()!=null?dialog.getWindow().getDecorView():activity==null?null:activity.getWindow().getDecorView();
            String ime="unknown";if(Build.VERSION.SDK_INT>=30&&decor!=null){WindowInsets insets=decor.getRootWindowInsets();if(insets!=null)ime=Boolean.toString(insets.isVisible(WindowInsets.Type.ime()));}
            return "elapsed="+SystemClock.elapsedRealtime()+", "+active+", ime="+ime+", dialogShowing="+(dialog==null?"untracked":dialog.isShowing());
        });
    }
    private static String normalized(CharSequence value){return value==null?"":value.toString().trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    private static <T>T main(Instrumentation instrumentation,Supplier<T> read) {
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{result.set(read.get());}catch(Throwable failure){error.set(failure);}});
        if(error.get()!=null)throw new AssertionError("Dialog state read failed",error.get());return result.get();
    }
}
