package ru.bububu.radio.core;

import java.util.*;

/** Live-session order only. Exact source identities and playback remain owned by PlayerController. */
public final class AirMix {
    public static final class Item {
        public final String station,article,version,source,key;
        public final int sentenceCount;
        public Item(String station,String article,String version,String source) {
            this(station,article,version,source,-1);
        }
        public Item(String station,String article,String version,String source,int sentenceCount) {
            this.sentenceCount=sentenceCount;this.station=Objects.requireNonNull(station);this.article=Objects.requireNonNull(article);
            this.version=Objects.requireNonNull(version);this.source=Objects.requireNonNull(source);
            key=station+"\n"+article+"\n"+version;
        }
    }
    private final Random random;
    private final Set<String> visited=new LinkedHashSet<>();
    private boolean enabled,pass,ended;
    private String lastSource;
    private final List<Item> order=new ArrayList<>();private int cursor=-1,reached=-1;
    public AirMix(Random random){this.random=Objects.requireNonNull(random);}
    public boolean enabled(){return enabled;}
    public boolean ended(){return ended;}
    public int visited(){return visited.size();}
    public void enabled(boolean value,Item actuallyPlaying){enabled=value;if(value&&actuallyPlaying!=null)started(actuallyPlaying);}
    public void explicitPlay(){if(enabled&&(!pass||ended)){visited.clear();lastSource=null;pass=true;ended=false;}}
    public void started(Item item){if(!enabled)return;if(!pass){pass=true;ended=false;}visited.add(item.key);lastSource=item.source;selected(item);}
    /** Caller supplies only current, admitted FULL ARTICLE_ANALYSIS source versions. */
    public Item next(Collection<Item> eligible){
        if(!enabled)return null;
        LinkedHashMap<String,Item> unique=new LinkedHashMap<>();
        for(Item item:eligible)if(!visited.contains(item.key))unique.put(item.key,item);
        if(unique.isEmpty()){ended=true;return null;}
        List<Item> candidates=new ArrayList<>();for(Item item:unique.values())if(!Objects.equals(lastSource,item.source))candidates.add(item);
        if(candidates.isEmpty())candidates.addAll(unique.values());
        return candidates.get(random.nextInt(candidates.size()));
    }
    /** Explicit owner action starts a new live shuffle; selected progress remains in the player. */
    public Item reshuffle(List<Item> pool,PlayerController.SavedState state){
        enabled=true;pass=true;ended=false;visited.clear();lastSource=null;order.clear();cursor=-1;reached=-1;
        Set<String> keys=new HashSet<>();for(Item item:pool)if(!complete(item,state)&&keys.add(item.key))order.add(item);
        Collections.shuffle(order,random);return order.isEmpty()?null:order.get(0);
    }
    public void selected(Item item){if(!enabled||item==null)return;for(int i=0;i<order.size();i++)if(order.get(i).key.equals(item.key)){cursor=i;reached=Math.max(reached,i);return;}}
    public Item previousInAir(List<Item> pool){if(!enabled)return null;for(int i=cursor-1;i>=0;i--)if(present(order.get(i),pool))return order.get(i);return null;}
    public boolean hasPreviousInAir(List<Item> pool){return previousInAir(pool)!=null;}
    public boolean hasPreviousInAir(List<Item> pool,List<String> stations,String station,Article article){return previousInAir(pool,stations,station,article)!=null;}
    /** Ordinary Back reverses the admitted air order; Mix retains its live history. */
    public Item previousInAir(List<Item> pool,List<String> stations,String station,Article article){
        if(enabled)return previousInAir(pool);if(article==null)return null;
        int rank=stations.indexOf(station),current=-1;if(rank<0)return null;
        for(int i=0;i<pool.size();i++){Item item=pool.get(i);if(item.station.equals(station)&&item.article.equals(article.id)&&item.version.equals(article.version)){current=i;break;}}
        if(current<0)return null;
        for(int i=current-1;i>=0;i--)if(pool.get(i).station.equals(station))return pool.get(i);
        for(int offset=1;offset<stations.size();offset++){String target=stations.get((rank-offset+stations.size())%stations.size());for(int i=pool.size()-1;i>=0;i--)if(pool.get(i).station.equals(target))return pool.get(i);}
        for(int i=pool.size()-1;i>current;i--)if(pool.get(i).station.equals(station))return pool.get(i);
        return null;
    }
    public Item nextInAir(List<Item> pool,List<String> stations,String station,Article article,PlayerController.SavedState state){return nextInAir(pool,stations,station,article,state,false);}
    public Item nextInAir(List<Item> pool,List<String> stations,String station,Article article,PlayerController.SavedState state,boolean explicit){
        return nextInAir(pool,stations,station,article,state,explicit,Collections.emptySet());
    }
    public Item nextInAir(List<Item> pool,List<String> stations,String station,Article article,PlayerController.SavedState state,boolean explicit,Set<String> excluded){
        if(!enabled){for(Item item:remaining(pool,stations,station,article,state))if(!excluded.contains(PlayerController.progressKey(item.station,item.article,item.version)))return item;return null;}
        // Retain only the current air's exact versions; live history stays within its existing bound.
        for(int i=order.size()-1;i>=0;i--)if(!present(order.get(i),pool)){order.remove(i);if(i<=cursor)cursor--;if(i<=reached)reached--;}
        List<Item> added=new ArrayList<>();Set<String> known=new HashSet<>();for(Item item:order)known.add(item.key);
        for(Item item:pool)if(!complete(item,state)&&known.add(item.key))added.add(item);Collections.shuffle(added,random);order.addAll(added);
        Item next=forward(pool,state,explicit,excluded);ended=next==null;return next;
    }
    public boolean hasNextInAir(List<Item> pool,List<String> stations,String station,Article article,PlayerController.SavedState state){
        return hasNextInAir(pool,stations,station,article,state,true);
    }
    /** Query only: the caller may require unread candidates without preparing shuffle order. */
    public boolean hasNextInAir(List<Item> pool,List<String> stations,String station,Article article,PlayerController.SavedState state,boolean explicit){
        if(!enabled)return !remaining(pool,stations,station,article,state).isEmpty();
        if(forward(pool,state,explicit)!=null)return true;Set<String> known=new HashSet<>();for(Item item:order)known.add(item.key);
        for(Item item:pool)if(!known.contains(item.key)&&!complete(item,state))return true;return false;
    }
    private Item forward(List<Item> pool,PlayerController.SavedState state,boolean explicit){
        return forward(pool,state,explicit,Collections.emptySet());
    }
    private Item forward(List<Item> pool,PlayerController.SavedState state,boolean explicit,Set<String> excluded){
        for(int i=cursor+1;i<order.size();i++){Item item=order.get(i);if(!excluded.contains(PlayerController.progressKey(item.station,item.article,item.version))&&present(item,pool)&&(explicit&&i<=reached||!complete(item,state)))return item;}return null;
    }
    private static boolean present(Item item,List<Item> pool){for(Item value:pool)if(value.key.equals(item.key))return true;return false;}
    private static boolean complete(Item item,PlayerController.SavedState state){
        PlayerController.SavedProgress p=state.progress.get(PlayerController.progressKey(item.station,item.article,item.version));
        if(p==null)return false;if(p.read)return true;
        if(item.sentenceCount<1)return p.passEnded;
        if(p.completedSentences.size()<item.sentenceCount)return false;
        Set<Integer> covered=new HashSet<>(p.completedSentences);if(covered.size()!=item.sentenceCount)return false;for(int i:covered)if(i<0||i>=item.sentenceCount)return false;return true;
    }
    /** Forward source order wraps once to earlier eligible sources; current exact item never repeats. */
    private List<Item> remaining(List<Item> pool,List<String> stations,String station,Article article,PlayerController.SavedState state){
        List<Item> result=new ArrayList<>();int rank=stations.indexOf(station),current=-1;if(rank<0)return result;
        for(int i=0;i<pool.size();i++){Item item=pool.get(i);if(item.station.equals(station)&&article!=null&&item.article.equals(article.id)&&item.version.equals(article.version)){current=i;break;}}
        for(int i=current+1;i<pool.size();i++){Item item=pool.get(i);if(item.station.equals(station)&&i!=current&&!complete(item,state))result.add(item);}
        for(int offset=1;offset<stations.size();offset++){String target=stations.get((rank+offset)%stations.size());for(Item item:pool)if(item.station.equals(target)&&!complete(item,state))result.add(item);}
        for(int i=0;i<current;i++){Item item=pool.get(i);if(item.station.equals(station)&&!complete(item,state))result.add(item);}
        return result;
    }
    /** New service/process never imports a stale pass, visited set or virtual station. */
    public void coldReset(){enabled=false;pass=false;ended=false;visited.clear();lastSource=null;order.clear();cursor=-1;reached=-1;}
}
