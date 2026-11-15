package ru.bububu.radio.models;
import java.io.*;
import java.util.function.BooleanSupplier;
public final class NativeModels implements ModelRuntime {
 public java.util.List<String> radioFragments(File pack,SileroSpeechInput.SentencePlan sentence,int maxChars)throws IOException{throw new IOException("Нужна experimental ARM64-версия приложения");}
 public byte[] synthesize(File p,String t,BooleanSupplier c)throws IOException{throw new IOException("Нужна experimental ARM64-версия приложения");}
 public String transcribe(File p,byte[] w,BooleanSupplier c)throws IOException{throw new IOException("Нужна experimental ARM64-версия приложения");}
 public String transcribeNote(File p,byte[] w,BooleanSupplier c)throws IOException{throw new IOException("Нужна experimental ARM64-версия приложения");}
}
