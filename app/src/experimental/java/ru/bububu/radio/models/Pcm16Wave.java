package ru.bububu.radio.models;

import java.io.IOException;

/** Strict single fmt16/data PCM16 mono 16k container bounded user-selected synthetic WAV input. */
final class Pcm16Wave {
    static float[] decode(byte[] b) throws IOException {
        return decode(b, 960044, "PCM16_MONO_16K_UP_TO30S_REQUIRED");
    }
    static float[] decodeNote(byte[] b) throws IOException {
        return decode(b, 3840044, "PCM16_MONO_16K_NOTE_UP_TO120S_REQUIRED");
    }
    private static float[] decode(byte[] b, int limit, String error) throws IOException {
        if (b==null || b.length<46 || b.length>limit || (b.length-44)%2!=0 || !tag(b,0,"RIFF") || u32(b,4) != b.length-8
                || !tag(b,8,"WAVE") || !tag(b,12,"fmt ") || u32(b,16) != 16
                || u16(b,20) != 1 || u16(b,22) != 1 || u32(b,24) != 16000
                || u32(b,28) != 32000 || u16(b,32) != 2 || u16(b,34) != 16
                || !tag(b,36,"data") || u32(b,40) != b.length-44)
            throw new IOException(error);
        float[] samples = new float[(b.length-44)/2];
        for (int i=0;i<samples.length;i++) samples[i]=(short)u16(b,44+2*i)/32768f;
        return samples;
    }
    private static boolean tag(byte[] b,int p,String s) {
        for(int i=0;i<s.length();i++) if(b[p+i]!=(byte)s.charAt(i)) return false;
        return true;
    }
    private static int u16(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8); }
    private static long u32(byte[] b,int p) { return u16(b,p)|((long)u16(b,p+2)<<16); }
    private Pcm16Wave() {}
}
