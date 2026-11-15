package ru.bububu.radio.models;
import java.io.*;
import java.util.function.BooleanSupplier;
public interface ModelRuntime {
 default java.util.List<String> radioFragments(File pack,String raw,int maxChars)throws Exception{return radioFragments(pack,SileroSpeechInput.forSentence(raw),maxChars);}
 java.util.List<String> radioFragments(File pack,SileroSpeechInput.SentencePlan sentence,int maxChars)throws Exception;
 default java.util.List<String> radioFragments45(File pack,ru.bububu.radio.sources.SourceSpeechPlan45.Plan plan,int maxChars)throws Exception{throw new IOException("Нужна experimental ARM64-версия приложения");}
 byte[] synthesize(File pack,String raw,BooleanSupplier cancelled)throws IOException;
 String transcribe(File pack,byte[] wav,BooleanSupplier cancelled)throws IOException;
}
