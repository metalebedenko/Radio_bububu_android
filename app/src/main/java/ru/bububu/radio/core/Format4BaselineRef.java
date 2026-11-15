package ru.bububu.radio.core;

/** Exact authoritative pre-format4 image; codecs1/2 keep their original meaning. */
public final class Format4BaselineRef {
    public final int baselineSchema,targetSchema=4,capsuleCodec=3;
    public final MigrationStamp stamp;
    public Format4BaselineRef(int schema,MigrationStamp stamp){
        if(schema<1||schema>3||stamp==null||stamp.baselineHash==null&&schema!=1)throw new IllegalArgumentException("Format4 baseline reference");
        baselineSchema=schema;this.stamp=stamp;
    }
}
