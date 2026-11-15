package ru.bububu.radio.core;

/** One pointer gesture: a short release or one timed hold, never both. */
public final class HoldGesture {
    public static final long HOLD_MS=3000;
    private final Runnable click,hold;
    private long began;
    private boolean active;
    public HoldGesture(Runnable click,Runnable hold){this.click=click;this.hold=hold;}
    public void down(long now){began=now;active=true;}
    public boolean active(){return active;}
    public void cancel(){active=false;}
    public void tick(long now){if(active&&now-began>=HOLD_MS){active=false;hold.run();}}
    public void up(long now){if(!active)return;if(now-began>=HOLD_MS)tick(now);else{active=false;click.run();}}
}
