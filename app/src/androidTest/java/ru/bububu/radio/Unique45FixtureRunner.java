package ru.bububu.radio;

import android.app.Application;
import android.content.Context;
import android.content.pm.InstrumentationInfo;
import androidx.test.runner.AndroidJUnitRunner;

/** Reject an accidental target before target Application construction/startup. */
public final class Unique45FixtureRunner extends AndroidJUnitRunner {
    @Override public Application newApplication(ClassLoader loader,String className,Context context)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        try{
            InstrumentationInfo info=getContext().getPackageManager().getInstrumentationInfo(getComponentName(),0);
            Unique45FixtureContract.identity(context.getPackageName(),getContext().getPackageName(),info.targetPackage);
        }catch(Exception rejected){throw new IllegalStateException("REFUSE_UNIQUE45_BEFORE_APPLICATION",rejected);}
        return super.newApplication(loader,className,context);
    }
}
