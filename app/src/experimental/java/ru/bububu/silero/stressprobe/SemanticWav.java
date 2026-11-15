package ru.bububu.silero.stressprobe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Canonical mono PCM16/24kHz output only, strictly positive and at most 1 MiB including the header (less than thirty seconds). */
final class SemanticWav {
    static final int MAX_BYTES=1048576,MAX_SAMPLES=(MAX_BYTES-44)/2;
    private SemanticWav(){}
    static void validate(byte[] data)throws IOException{
        if(data==null||data.length<46||data.length>MAX_BYTES||(data.length-44)%2!=0)throw new IOException("SEMANTIC_WAV_BOUND");
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt(0)!=0x46464952||b.getInt(4)!=data.length-8||b.getInt(8)!=0x45564157||b.getInt(12)!=0x20746d66
                ||b.getInt(16)!=16||b.getShort(20)!=1||b.getShort(22)!=1||b.getInt(24)!=24000||b.getInt(28)!=48000
                ||b.getShort(32)!=2||b.getShort(34)!=16||b.getInt(36)!=0x61746164||b.getInt(40)!=data.length-44)throw new IOException("SEMANTIC_WAV_HEADER");
    }
}
