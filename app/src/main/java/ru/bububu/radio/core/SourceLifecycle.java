package ru.bububu.radio.core;

/** Connection lifetime, independent of the existing refresh-enabled setting. */
public final class SourceLifecycle {
    public static final int ACTIVE=1, RETIRED=2;
    public final int status;
    public final long generation,lastDeletionSequence;
    public SourceLifecycle(int status,long generation,long lastDeletionSequence) {
        if((status!=ACTIVE&&status!=RETIRED)||generation<0||lastDeletionSequence<0
                ||((generation==0)!=(lastDeletionSequence==0))||(status==RETIRED&&generation==0))
            throw new IllegalArgumentException("Source lifecycle");
        this.status=status;this.generation=generation;this.lastDeletionSequence=lastDeletionSequence;
    }
    public static SourceLifecycle active(){return new SourceLifecycle(ACTIVE,0,0);}
}
