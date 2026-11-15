package ru.bububu.radio.core;

/** Exact schema2 baseline; the original schema1 MigrationStamp remains separate. */
public final class UpgradeRollbackRef {
    public final int baselineSchema,capsuleCodec;
    public final MigrationStamp stamp;
    public UpgradeRollbackRef(int baselineSchema,int capsuleCodec,MigrationStamp stamp) {
        if(baselineSchema!=2||capsuleCodec!=2||stamp==null||stamp.baselineHash==null||stamp.absenceEpoch!=null)
            throw new IllegalArgumentException("Schema3 rollback reference");
        this.baselineSchema=baselineSchema;this.capsuleCodec=capsuleCodec;this.stamp=stamp;
    }
}
