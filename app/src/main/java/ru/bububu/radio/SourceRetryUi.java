package ru.bububu.radio;

import android.view.View;
import android.widget.Button;
import java.util.function.Supplier;
import ru.bububu.radio.sources.SourceClock;
import ru.bububu.radio.sources.SourceFailureInfo;

/** Refreshes visible controls only; reaching the deadline never starts a request. */
final class SourceRetryUi implements View.OnAttachStateChangeListener, Runnable {
    private final Button button;
    private final String label;
    private final Supplier<SourceFailureInfo> failure;
    private final SourceClock clock;
    private SourceRetryUi(Button button,String label,Supplier<SourceFailureInfo> failure,SourceClock clock) {
        this.button=button;this.label=label;this.failure=failure;this.clock=clock;
    }
    static SourceRetryUi bind(Button button,String label,Supplier<SourceFailureInfo> failure,SourceClock clock) {
        SourceRetryUi ui=new SourceRetryUi(button,label,failure,clock);
        button.addOnAttachStateChangeListener(ui);ui.refresh();
        if(button.isAttachedToWindow())ui.run();
        return ui;
    }
    void refresh() {
        SourceFailureInfo current=failure.get();boolean blocked=current!=null&&current.retryBlocked(clock);
        button.setEnabled(!blocked);
        button.setText(blocked?label+"\n"+current.message(clock):label);
    }
    @Override public void run() { refresh();if(button.isAttachedToWindow())button.postDelayed(this,1000); }
    @Override public void onViewAttachedToWindow(View view) { button.removeCallbacks(this);run(); }
    @Override public void onViewDetachedFromWindow(View view) { button.removeCallbacks(this); }
}
