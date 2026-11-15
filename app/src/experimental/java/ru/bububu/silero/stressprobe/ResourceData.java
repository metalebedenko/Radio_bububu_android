package ru.bububu.silero.stressprobe;
import java.util.*;
import org.json.*;

/** Exact C341 JSON data only; no compiled Python regex/code/default-empty resources. */
final class ResourceData {
 static FrontendPlan.Data parse(byte[] bytes) throws Exception {
  FrontendPlan.need(bytes.length==9835920,"RESOURCE_SIZE");JSONObject root=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
  JSONObject a=root.getJSONObject("accentor"),h=root.getJSONObject("homosolver"),t=root.getJSONObject("tokenizer");FrontendPlan.Data d=new FrontendPlan.Data();
  FrontendPlan.need(a.getJSONObject("ngram_ids").length()==126523&&a.getInt("UNK")==126521&&a.getInt("embedding_rows")==126523,"NGRAM_RESOURCE_CONTRACT");
  JSONObject cfg=t.getJSONObject("effective_config"),basic=t.getJSONObject("basic"),wp=t.getJSONObject("wordpiece");
  FrontendPlan.need(cfg.getBoolean("tokenize_chinese_chars")&&basic.getBoolean("tokenize_chinese_chars")&&cfg.getInt("model_max_length")==2048&&wp.getInt("max_input_chars_per_word")==100,"TOKENIZER_CONFIG");
  Set<String> never=new HashSet<>();JSONArray ns=basic.getJSONArray("never_split");for(int i=0;i<ns.length();i++) never.add(ns.getString(i));
  FrontendPlan.need(never.equals(new HashSet<>(Arrays.asList("[HOMO]","[/HOMO]"))),"TOKENIZER_NEVER_SPLIT");
  JSONObject special=t.getJSONObject("special_tokens_and_ids");d.cls=special.getInt("cls_token_id");d.sep=special.getInt("sep_token_id");d.unk=special.getInt("unk_token_id");d.homoStart=special.getInt("homo_start_id");d.homoEnd=special.getInt("homo_end_id");
  FrontendPlan.need(d.cls==2&&d.sep==3&&d.unk==1&&d.homoStart==83828&&d.homoEnd==83829,"SPECIAL_TOKEN_CONTRACT");
  JSONArray vocab=t.getJSONArray("effective_merged_vocab_pairs");FrontendPlan.need(vocab.length()==83830,"VOCAB_COUNT");
  for(int i=0;i<vocab.length();i++) { JSONArray pair=vocab.getJSONArray(i);FrontendPlan.need(pair.length()==2,"VOCAB_PAIR");FrontendPlan.need(d.vocab.put(pair.getString(0),pair.getInt(1))==null,"VOCAB_DUPLICATE"); }
  FrontendPlan.need(d.vocab.get("[CLS]")==d.cls&&d.vocab.get("[SEP]")==d.sep&&d.vocab.get("[HOMO]")==d.homoStart&&d.vocab.get("[/HOMO]")==d.homoEnd,"VOCAB_SPECIAL_IDS");
  JSONObject variants=h.getJSONObject("ordered_variants");FrontendPlan.need(variants.length()==2208,"HOMODICT_COUNT");
  for(Iterator<String> it=variants.keys();it.hasNext();) { String key=it.next();JSONArray ar=variants.getJSONArray(key);List<String> values=new ArrayList<>();for(int i=0;i<ar.length();i++) values.add(ar.getString(i));d.variants.put(key,Collections.unmodifiableList(values)); }
  JSONObject phrases=h.getJSONObject("compiled_phrases");FrontendPlan.need(phrases.length()==1477&&h.getJSONObject("compiled_user_phrases").length()==0&&h.getJSONObject("compiled_user_patterns").length()==0,"PHRASE_RESOURCE_CONTRACT");
  for(Iterator<String> it=phrases.keys();it.hasNext();) { String key=it.next();JSONObject v=phrases.getJSONObject(key);FrontendPlan.need(v.getInt("flags")==34,"PYTHON_REGEX_FLAGS");d.phrases.put(key,v.getString("pattern"));JSONObject groups=v.getJSONObject("groupindex");Map<String,Integer> indices=new HashMap<>();for(Iterator<String> names=groups.keys();names.hasNext();){String name=names.next();indices.put(name,groups.getInt(name));}d.phraseGroups.put(key,indices); }
  JSONObject exceptions=a.getJSONObject("exceptions");FrontendPlan.need(exceptions.length()==19660,"EXCEPTIONS_COUNT");
  for(Iterator<String> it=exceptions.keys();it.hasNext();) { String key=it.next();JSONArray ar=exceptions.getJSONArray(key);FrontendPlan.need(ar.length()==2,"EXCEPTION_PAIR");d.exceptions.put(key,new int[]{ar.getInt(0),ar.getInt(1)}); }
  return d;
 }
 private ResourceData() {}
}
