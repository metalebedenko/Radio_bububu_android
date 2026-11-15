package ru.bububu.radio.sources;

import java.net.InetAddress;
import java.util.Arrays;

/** Conservative IANA special-address policy. It does not use DNS to classify an address. */
public class PublicIpPolicy {
    public static final String VERSION = "iana-public-2026-10-v1";
    public boolean isPublic(InetAddress address) {
        if(address==null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress())return false;
        byte[] b=address.getAddress();
        if(b.length==4)return publicV4(b);
        if(b.length!=16)return false;
        if(mapped(b))return publicV4(Arrays.copyOfRange(b,12,16));
        // Well-known NAT64 translation is accepted only for a public embedded IPv4 address.
        if(prefix(b,new byte[]{0,0x64,(byte)0xff,(byte)0x9b,0,0,0,0,0,0,0,0},96))return publicV4(Arrays.copyOfRange(b,12,16));
        // Only global-unicast space; this also rejects ULA, local NAT64 and scoped addresses.
        if((b[0]&0xe0)!=0x20)return false;
        if(prefix(b,new byte[]{0x20,0x01,0,0},23))return false;
        if(prefix(b,new byte[]{0x20,0x01,0x0d,(byte)0xb8},32))return false;
        if(prefix(b,new byte[]{0x3f,(byte)0xff,0},20))return false;
        if((b[0]&255)==0x20 && (b[1]&255)==2)return publicV4(Arrays.copyOfRange(b,2,6));
        return true;
    }
    private static boolean mapped(byte[] b) {
        for(int i=0;i<10;i++)if(b[i]!=0)return false;
        return b[10]==(byte)255 && b[11]==(byte)255;
    }
    private static boolean publicV4(byte[] b) {
        int a=b[0]&255,c=b[1]&255,d=b[2]&255;
        if(a==0 || a==10 || a==127 || a>=224)return false;
        if(a==100 && c>=64 && c<=127)return false;
        if(a==169 && c==254 || a==172 && c>=16 && c<=31)return false;
        if(a==192 && (c==168 || c==0 && (d==0 || d==2) || c==88 && d==99 || c==31 && d==196 || c==52 && d==193 || c==175 && d==48))return false;
        if(a==198 && (c==18 || c==19 || c==51 && d==100))return false;
        return !(a==203 && c==0 && d==113);
    }
    private static boolean prefix(byte[] value,byte[] network,int bits) {
        int whole=bits/8; for(int i=0;i<whole;i++)if(value[i]!=network[i])return false;
        int remaining=bits%8;
        return remaining==0 || ((value[whole]^network[whole])&(0xff<<(8-remaining)))==0;
    }
    public void require(InetAddress address) throws SourceFailure { if(!isPublic(address))throw new SourceFailure("NON_PUBLIC_ADDRESS"); }
}
