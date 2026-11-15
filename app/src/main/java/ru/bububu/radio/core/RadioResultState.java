package ru.bububu.radio.core;

/** Presentation only: the completed report and downloaded bodies remain owned by their services. */
public final class RadioResultState {
    private String dismissedErrorRun;
    public boolean visible(String run,boolean finished,boolean failed,boolean preparingAir,boolean decisionPending){
        if(!finished)return true;
        if(errorVisible(run,failed))return true;
        return !preparingAir&&decisionPending;
    }
    public boolean errorVisible(String run,boolean failed){return failed&&!java.util.Objects.equals(run,dismissedErrorRun);}
    public void revealErrors(String run){if(java.util.Objects.equals(run,dismissedErrorRun))dismissedErrorRun=null;}
    public void dismiss(String run){dismissedErrorRun=run;}
}
