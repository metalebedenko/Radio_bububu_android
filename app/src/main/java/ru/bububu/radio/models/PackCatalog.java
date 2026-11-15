package ru.bububu.radio.models;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class PackCatalog {
 public static final class Entry { public final String name,sha;public final long bytes;public Entry(String n,long b,String s){name=n;bytes=b;sha=s;} }
 public final String kind;public final int version;public final List<Entry> files;public final long bytes;
 public PackCatalog(String k,int v,Entry...entries){kind=k;version=v;files=Collections.unmodifiableList(Arrays.asList(entries));long sum=0;for(Entry e:files)sum=Math.addExact(sum,e.bytes);bytes=sum;}
 public byte[] manifest(){StringBuilder s=new StringBuilder("RADIO_BUBUBU_PACK_V1\n").append(kind).append('\n').append(version).append('\n');for(Entry e:files)s.append(e.name).append('\t').append(e.bytes).append('\t').append(e.sha).append('\n');return s.toString().getBytes(StandardCharsets.UTF_8);}
 public Entry entry(String name){for(Entry e:files)if(e.name.equals(name))return e;return null;}
 public static final PackCatalog SILERO=new PackCatalog("silero",1,
  new Entry("core.pt",91551897,"3d941aab98eeef6f48bee02d7a546f2cca2439d936fd727f2801b5fcd8fd9ff1"),
  new Entry("homosolver.pt",39049200,"d695e3d70a7dcca3171ab94c80c05b30368cace4529d827cdab58a8fd4389b5b"),
  new Entry("accentor.pt",10997270,"066d5b01e09aaed3ae267be77466aa875e8a9405cc48c42b562b026165699413"),
  new Entry("resources.json",9835920,"8b280e0a8ea7798aa06fe9b4dbff0bfaede7316a9fa274cf6ca67c37a68ecef3"));
 public static final PackCatalog GIGAAM=new PackCatalog("gigaam",1,
  new Entry("encoder.int8.onnx",224570820,"369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf"),
  new Entry("decoder.onnx",4600132,"38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4"),
  new Entry("joiner.onnx",2712896,"602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
  new Entry("tokens.txt",13354,"39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7"));
 private PackCatalog() {throw new AssertionError();}
}
