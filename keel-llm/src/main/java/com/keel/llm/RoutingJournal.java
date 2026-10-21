package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.*;

/** Only routing metadata; never stores messages, prompts or credentials. */
final class RoutingJournal {
    private static final String KEY="keel-llm:routing-requests:v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final RedisCommands<String,String> redis;
    private final ArrayDeque<String> memory=new ArrayDeque<>();
    record Entry(String id,String alias,String logical,int inputEstimate,long at) {}
    RoutingJournal(String uri) { redis=uri==null||uri.isBlank()?null:RedisClient.create(uri).connect().sync(); }
    synchronized void record(String id,String alias,String logical,int estimate) {
        try {
            String raw=JSON.writeValueAsString(new Entry(id,alias,logical,estimate,Instant.now().getEpochSecond()));
            if(redis!=null) { redis.rpush(KEY,raw); redis.ltrim(KEY,-100000,-1); }
            else {memory.addLast(raw);if(memory.size()>100000)memory.removeFirst();}
        } catch(Exception e){ org.slf4j.LoggerFactory.getLogger(RoutingJournal.class).warn("routing journal unavailable trace_id={} agent={}",id,alias); }
    }
    synchronized List<Entry> recent() {
        var rows=new ArrayList<Entry>(); long cutoff=Instant.now().minusSeconds(86400).getEpochSecond();
        for(String raw:redis==null?new ArrayList<>(memory):redis.lrange(KEY,0,-1)) {
            try {var row=JSON.readValue(raw,Entry.class);if(row.at()>=cutoff)rows.add(row);}catch(Exception e){throw new IllegalStateException(e);}
        }
        return rows;
    }
}
