package ru.bububu.radio.models;
import java.io.*;
public final class PackGuard {
 public static String path(File pack,PackCatalog catalog,String name)throws IOException {PackCatalog.Entry e=catalog.entry(name);if(e==null)throw new IOException("Неизвестный файл модели");FileGuards.Root root=FileGuards.anchorOwnedFilesDirectory(pack);return FileGuards.verify(root,new FileGuards.Spec(name,e.bytes,e.sha,268435456)).reverifiedPath();}
 public static byte[] resources(File pack)throws IOException {PackCatalog.Entry e=PackCatalog.SILERO.entry("resources.json");return FileGuards.readResources(FileGuards.anchorOwnedFilesDirectory(pack),new FileGuards.Spec(e.name,e.bytes,e.sha,16777216));}
 private PackGuard(){}
}
