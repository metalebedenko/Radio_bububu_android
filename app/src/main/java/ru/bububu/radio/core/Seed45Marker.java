package ru.bububu.radio.core;

import java.util.*;
import ru.bububu.radio.sources.SourceLimits;

/** Immutable decision, retained when the user later removes every seeded station. */
public final class Seed45Marker {
    public static final String POLICY="source-defaults45-v1";
    public final String attemptId,policy,definitionSetHash;
    public final Map<String,String> bindings;
    public Seed45Marker(String attempt,String policy,String definitions,Map<String,String> bindings){
        if(!UUID.fromString(attempt).toString().equals(attempt)||!POLICY.equals(policy)||!definitions.matches("[0-9a-f]{64}")||bindings.size()!=6)throw new IllegalArgumentException("Seed marker");
        TreeMap<String,String> sorted=new TreeMap<>();Set<String> ids=new HashSet<>();for(Map.Entry<String,String> e:bindings.entrySet()){
            SourceLimits.value(e.getKey(),128,"seed channel",false);if(e.getKey().isEmpty()||!UUID.fromString(e.getValue()).toString().equals(e.getValue())||!ids.add(e.getValue()))throw new IllegalArgumentException("Seed binding");sorted.put(e.getKey(),e.getValue());
        }
        attemptId=attempt;this.policy=policy;definitionSetHash=definitions;this.bindings=Collections.unmodifiableMap(sorted);
    }
}
