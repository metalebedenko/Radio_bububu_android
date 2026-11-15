package ru.bububu.radio.core;

import java.util.UUID;
import ru.bububu.radio.sources.SourceLimits;

/** This reference becomes committed only in the authoritative schema2 snapshot. */
public final class MigrationStamp {
    public final String attemptId,baselineHash,absenceEpoch,capsuleId,capsuleHash;
    public final int baselineLength,capsuleLength;
    public MigrationStamp(String attemptId,String baselineHash,int baselineLength,String absenceEpoch,String capsuleId,int capsuleLength,String capsuleHash) {
        uuid(attemptId); uuid(capsuleId);
        if(!attemptId.equals(capsuleId)) throw new IllegalArgumentException("capsule attempt");
        if(baselineHash==null) { uuid(absenceEpoch); if(baselineLength!=-1) throw new IllegalArgumentException("absent length"); }
        else if(!baselineHash.matches("[0-9a-f]{64}")||baselineLength<0||baselineLength>NoteBook.MAX_METADATA_BYTES||absenceEpoch!=null) throw new IllegalArgumentException("baseline");
        if(capsuleLength<1||capsuleLength>NoteBook.MAX_METADATA_BYTES+16*1024||!capsuleHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("capsule");
        this.attemptId=attemptId; this.baselineHash=baselineHash; this.baselineLength=baselineLength; this.absenceEpoch=absenceEpoch;
        this.capsuleId=capsuleId; this.capsuleLength=capsuleLength; this.capsuleHash=capsuleHash;
    }
    public boolean matches(byte[] baseline) {
        return baseline==null ? baselineHash==null : baselineHash!=null && baselineLength==baseline.length && baselineHash.equals(SourceLimits.hash(baseline));
    }
    private static void uuid(String value) { if(!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("UUID"); }
}
