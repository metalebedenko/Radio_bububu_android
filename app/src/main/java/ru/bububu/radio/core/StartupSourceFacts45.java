package ru.bububu.radio.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import ru.bububu.radio.models.PackCatalog;
import ru.bububu.radio.sources.SourceLimits;

/** Inert prior-use proof. No SharedPreferences/AtomicFile/model constructors or recovery. */
public final class StartupSourceFacts45 {
    public enum State { PROVEN_FRESH, LEGACY_PRESERVE, UNCERTAIN }
    public static final String RECEIPT="source-defaults45.seed";
    public static final class Snapshot {
        public final State state;public final Map<String,String> facts;
        private Snapshot(State state,Map<String,String> facts){this.state=state;this.facts=Collections.unmodifiableMap(new TreeMap<>(facts));}
        public byte[] manifest(){try{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);out.writeInt(1);out.writeInt(facts.size());for(Map.Entry<String,String> e:facts.entrySet()){write(out,e.getKey());write(out,e.getValue());}out.flush();if(b.size()>16384)throw new IllegalStateException("Fresh proof bound");return b.toByteArray();}catch(IOException impossible){throw new AssertionError(impossible);}}
        private static void write(DataOutputStream out,String s)throws IOException{out.writeInt(s.length());for(int n=0;n<s.length();n++)out.writeChar(s.charAt(n));}
    }
    public final File files,noBackup,prefs,notes;
    public StartupSourceFacts45(File files,File noBackup,File prefs){this.files=files;this.noBackup=noBackup;this.prefs=prefs;notes=new File(noBackup,"notes");}
    public Snapshot capture(){return capture(null,false);}
    /** Only a validated PREPARED owner may mask its exact lone Book temporary as originally absent. */
    public Snapshot capture(byte[] ownedTemporary){return capture(ownedTemporary,false);}
    public Snapshot capture(byte[] ownedTemporary,boolean ownedReceipt){
        Capture c=new Capture();try{
            c.directory("data",prefs.getParentFile(),true);c.directory("files",files,true);c.directory("nobackup",noBackup,true);c.directory("prefs",prefs,false);c.directory("notes",notes,false);
            boolean committedBook=false,unfinishedBook=false,committedPlayer=false,unfinishedPlayer=false;
            for(String suffix:List.of("",".bak",".new")){
                File book=new File(notes,NoteMetadataReader.BASE+suffix);byte[] b=c.file("notes/"+book.getName(),book,NoteBook.MAX_METADATA_BYTES,true);
                if(b!=null){if(ownedTemporary!=null&&suffix.equals(".new")&&Arrays.equals(b,ownedTemporary))c.facts.put("notes/"+book.getName(),"ABSENT");else{if(suffix.equals(".new"))unfinishedBook=true;else committedBook=true;try{NoteBook.validateImage(b);c.used=true;}catch(RuntimeException invalid){c.uncertain=true;}}}
                File player=new File(files,"player-state-v1.json"+suffix);byte[] p=c.file("files/"+player.getName(),player,SourceLimits.MAX_RADIO_BYTES,true);if(p!=null){if(suffix.equals(".new"))unfinishedPlayer=true;else committedPlayer=true;if(p.length==0)c.uncertain=true;else c.used=true;}
                File receipt=new File(notes,RECEIPT+suffix);byte[] receiptBytes=c.file("notes/"+receipt.getName(),receipt,65536,true);if(receiptBytes!=null){if(ownedReceipt)c.facts.put("notes/"+receipt.getName(),"ABSENT");else c.uncertain=true;}
            }
            if(unfinishedBook&&!committedBook||unfinishedPlayer&&!committedPlayer)c.uncertain=true;
            Map<String,Map<String,String>> known=new LinkedHashMap<>();known.put("demo_ui_v1",Map.of("skin","string","batch_source_ids","set","batch_per_source","int"));known.put("local-model-choice",Map.of("silero","boolean","giga-notes","boolean","bundled-v1","boolean"));
            // Main and job-specific applicationIds can make getLocalClassName return the full class.
            for(String activity:List.of("RadioActivity","ru.bububu.radio.RadioActivity"))known.put(activity,Map.of("notificationsAsked","boolean"));
            Set<String> allowedPrefs=new HashSet<>();for(Map.Entry<String,Map<String,String>> ns:known.entrySet())for(String suffix:List.of(".xml",".xml.bak")){
                String name=ns.getKey()+suffix;allowedPrefs.add(name);byte[] bytes=c.file("prefs/"+name,new File(prefs,name),1024*1024,true);
                if(bytes!=null){try{Map<String,String> keys=preferences(bytes);for(Map.Entry<String,String> key:keys.entrySet()){String expected=ns.getValue().get(key.getKey());String type=key.getValue().substring(0,key.getValue().indexOf(':'));if(expected!=null&&!expected.equals(type))throw new IOException("Preference type");c.facts.put("prefs-key/"+ns.getKey()+"/"+key.getKey(),key.getValue());}c.used=true;}catch(Exception invalid){c.uncertain=true;}}
            }
            for(File f:c.children(prefs))if(!allowedPrefs.contains(f.getName())){c.uncertain=true;c.inventory("prefs-extra/"+f.getName(),f,0);}
            Set<String> bookNames=new HashSet<>();for(String base:List.of(NoteMetadataReader.BASE,RECEIPT))for(String suffix:List.of("",".bak",".new"))bookNames.add(base+suffix);
            for(File f:c.children(notes))if(!bookNames.contains(f.getName())){c.inventory("notes-record/"+f.getName(),f,0);c.used=true;}
            for(String name:List.of("source-cas","source-network")){File root=new File(noBackup,name);c.directory(name,root,false);for(File f:c.children(root)){c.inventory(name+"/"+f.getName(),f,0);c.used=true;}}
            modelFacts(c);
            // A second identity/layout read catches replacement while values were inspected.
            c.recheck();
        }catch(Exception inspection){c.uncertain=true;}
        return new Snapshot(c.uncertain?State.UNCERTAIN:c.used?State.LEGACY_PRESERVE:State.PROVEN_FRESH,c.facts);
    }
    private void modelFacts(Capture c)throws IOException{
        File root=new File(files,"model-packs");c.directory("model-packs",root,false);Set<String> owned=new HashSet<>();
        for(PackCatalog catalog:List.of(PackCatalog.SILERO,PackCatalog.GIGAAM)){
            String pointerName=catalog.kind+".ready";byte[] pointer=c.file("model-packs/"+pointerName,new File(root,pointerName),96,true);if(pointer==null)continue;owned.add(pointerName);
            String generation=new String(pointer,StandardCharsets.US_ASCII);if(!generation.matches(catalog.kind+"-g-[a-f0-9-]{36}")){c.uncertain=true;continue;}
            try{UUID.fromString(generation.substring((catalog.kind+"-g-").length()));}catch(IllegalArgumentException invalid){c.uncertain=true;continue;}
            File dir=new File(root,generation);owned.add(generation);c.directory("model-packs/"+generation,dir,true);byte[] manifest=c.file("model-packs/"+generation+"/manifest.txt",new File(dir,"manifest.txt"),2048,true);
            boolean valid=manifest!=null&&Arrays.equals(manifest,catalog.manifest());Set<String> files=new HashSet<>();files.add("manifest.txt");
            for(PackCatalog.Entry entry:catalog.files){files.add(entry.name);File file=new File(dir,entry.name);BasicFileAttributes a=c.attributes(file);if(a==null||!a.isRegularFile()||a.size()!=entry.bytes)valid=false;else c.inventory("model-packs/"+generation+"/"+entry.name,file,0);}
            for(File file:c.children(dir))if(!files.contains(file.getName()))valid=false;if(valid)c.used=true;else c.uncertain=true;
        }
        for(File f:c.children(root))if(!owned.contains(f.getName())){c.inventory("model-extra/"+f.getName(),f,0);c.uncertain=true;}
    }
    private static final class Capture {
        final Map<String,String> facts=new TreeMap<>(),identities=new LinkedHashMap<>();final Map<String,File> paths=new LinkedHashMap<>();boolean used,uncertain;
        BasicFileAttributes attributes(File f)throws IOException{try{return Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(NoSuchFileException absent){File parent=f.getParentFile();if(parent!=null){BasicFileAttributes a=attributes(parent);if(a!=null&&!a.isDirectory())throw new IOException("Parent type");}return null;}}
        String identity(BasicFileAttributes a,boolean directory)throws IOException{if(a==null)return "ABSENT";if(a.fileKey()==null)throw new IOException("Unprovable file identity");return (directory?"DIR:":"FILE:")+a.fileKey()+(directory?"":":"+a.size()+":"+a.lastModifiedTime());}
        void track(String key,File f,BasicFileAttributes a,boolean directory)throws IOException{String id=identity(a,directory);paths.put(key,f);identities.put(key,id);facts.put(key,id);}
        void directory(String key,File f,boolean required)throws IOException{BasicFileAttributes a=attributes(f);if(a==null){if(required)throw new IOException("Required root missing");track(key,f,null,true);return;}if(!a.isDirectory()||!f.canRead()||!f.canExecute())throw new IOException("Directory inaccessible/type");track(key,f,a,true);}
        byte[] file(String key,File f,int bound,boolean digest)throws IOException{
            BasicFileAttributes a=attributes(f);if(a==null){track(key,f,null,false);return null;}if(!a.isRegularFile()||!f.canRead()||a.size()>bound)throw new IOException("File inaccessible/type/limit");byte[] bytes=Files.readAllBytes(f.toPath());if(bytes.length!=a.size()||bytes.length>bound)throw new IOException("File changed");track(key,f,a,false);if(digest)facts.put(key,facts.get(key)+":"+SourceLimits.hash(bytes));BasicFileAttributes after=attributes(f);if(!identity(a,false).equals(identity(after,false))||!Arrays.equals(bytes,Files.readAllBytes(f.toPath())))throw new IOException("File changed while reading");return bytes;
        }
        File[] children(File dir)throws IOException{BasicFileAttributes a=attributes(dir);if(a==null)return new File[0];if(!a.isDirectory())throw new IOException("Directory type");File[] list=dir.listFiles();if(list==null||list.length>10000)throw new IOException("Directory inventory");Arrays.sort(list,Comparator.comparing(File::getName));return list;}
        void inventory(String key,File f,int depth)throws IOException{if(depth>8||facts.size()>20000)throw new IOException("Inventory bound");BasicFileAttributes a=attributes(f);if(a==null)throw new IOException("Inventory raced");if(a.isDirectory()){directory(key,f,true);for(File child:children(f))inventory(key+"/"+child.getName(),child,depth+1);}else{if(!a.isRegularFile()||!f.canRead())throw new IOException("Inventory type/access");track(key,f,a,false);}}
        void recheck()throws IOException{for(Map.Entry<String,File> e:paths.entrySet()){String original=identities.get(e.getKey());BasicFileAttributes now=attributes(e.getValue());boolean directory=original.startsWith("DIR:");if(!original.equals(identity(now,directory)))throw new IOException("Layout changed during probe");}}
    }
    private static Map<String,String> preferences(byte[] bytes)throws Exception{
        SAXParserFactory f=SAXParserFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);XMLReader reader=f.newSAXParser().getXMLReader();Prefs handler=new Prefs();reader.setContentHandler(handler);reader.setErrorHandler(handler);reader.setEntityResolver((publicId,systemId)->{throw new SAXException("Preference external entity");});reader.parse(new InputSource(new ByteArrayInputStream(bytes)));if(handler.depth!=0||!handler.root)throw new IOException("Preference map");return handler.keys;
    }
    private static final class Prefs extends DefaultHandler {
        final Map<String,String> keys=new TreeMap<>();int depth,tokens;boolean root;String name,type;StringBuilder value;
        @Override public void startElement(String ns,String local,String q,Attributes a)throws SAXException{
            if(!ns.isEmpty()||++tokens>50000||++depth>64)throw new SAXException("Preference bounds");if(depth==1){if(root||!local.equals("map"))throw new SAXException("Preference root");root=true;return;}
            if(depth==2){if(!Set.of("string","int","long","float","boolean","set").contains(local))throw new SAXException("Preference type");name=a.getValue("name");if(name==null||name.length()>512||keys.containsKey(name))throw new SAXException("Preference key");type=local;value=new StringBuilder();String attr=a.getValue("value");if(attr!=null)value.append(attr);if(local.equals("boolean")&&!"true".equals(attr)&&!"false".equals(attr))throw new SAXException("Preference boolean");if(local.equals("int")||local.equals("long"))try{if(local.equals("int"))Integer.parseInt(attr);else Long.parseLong(attr);}catch(RuntimeException bad){throw new SAXException("Preference integer");}if(local.equals("float"))try{if(!Float.isFinite(Float.parseFloat(attr)))throw new IllegalArgumentException();}catch(RuntimeException bad){throw new SAXException("Preference float");}}
            else if(depth!=3||!"set".equals(type)||!local.equals("string"))throw new SAXException("Preference nested value");
        }
        @Override public void characters(char[] chars,int start,int length)throws SAXException{if(value!=null){if(value.length()>1024*1024-length)throw new SAXException("Preference value limit");value.append(chars,start,length);}}
        @Override public void endElement(String ns,String local,String q)throws SAXException{if(depth==2){keys.put(name,type+":"+SourceLimits.sha256(value.toString()));name=null;type=null;value=null;}depth--;if(depth<0)throw new SAXException("Preference layout");}
        @Override public void error(SAXParseException e)throws SAXException{throw e;}
        @Override public void fatalError(SAXParseException e)throws SAXException{throw e;}
    }
}
