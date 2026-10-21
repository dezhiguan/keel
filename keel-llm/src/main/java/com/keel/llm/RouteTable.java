package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;

/** One CAS-protected document: shared draft and immutable published snapshots. */
public final class RouteTable implements AutoCloseable {
    private static final String KEY = "keel-llm:logical-routing:v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RedisCommands<String,String> redis;
    private final RedisClient client;
    private String memory;
    public record Rule(String logical, String env, String primary, String secondary, int primaryWeight,
                       String fallback, int grayPercent, String grayModel, int minInputTokens) {
        public List<String> targets() {
            var names = new LinkedHashSet<String>();
            if (grayPercent < 100 && primaryWeight > 0) names.add(primary);
            if (grayPercent < 100 && primaryWeight < 100) names.add(secondary);
            if (grayPercent > 0) names.add(grayModel);
            if (Set.of("alternate", "latency", "cost").contains(fallback)) {
                names.add(primary); names.add(secondary);
            } else if (!"none".equals(fallback)) names.add(fallback);
            names.remove(""); names.remove(null);
            return List.copyOf(names);
        }
        public String select(String id) {
            if (grayPercent > 0 && roll(id,"gray") < grayPercent) return grayModel;
            return roll(id,"weight") < primaryWeight ? primary : secondary;
        }
    }
    private static int roll(String id,String salt) {
        try {var digest=java.security.MessageDigest.getInstance("SHA-256").digest((salt+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Math.floorMod(java.nio.ByteBuffer.wrap(digest).getInt(),100);
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public record State(long revision, int version, List<Rule> rules, List<Rule> draft, List<List<Rule>> history) {}
    public RouteTable(String uri) {
        client = uri == null || uri.isBlank() ? null : RedisClient.create(uri);
        redis = client==null?null:client.connect().sync();
        var seed = LogicalRoutes.ROWS.stream().map(r -> new Rule(r.logical(), "prod".equals(r.env()) ? "prod" : "staging",
                r.primary(),r.secondary(),r.primaryWeight(),"无".equals(r.fallback()) ? "none" : "按延迟".equals(r.fallback()) ? "latency" : r.fallback(),0,"",0)).toList();
        memory = encode(new State(1,1,seed,seed,List.of()));
        if (redis != null) redis.setnx(KEY,memory);
    }
    @Override public void close() { if(client!=null)client.shutdown(); }
    public State current() { return decode(redis == null ? memory : redis.get(KEY)); }
    public static Rule match(List<Rule> rules,String name,String env) {
        String effective = Set.of("dev","test").contains(env) ? "staging" : env;
        return rules.stream().filter(r -> r.logical().equals(name) && r.env().equals(effective)).findFirst().orElse(null);
    }
    public boolean logical(String name) { return current().rules().stream().anyMatch(r -> r.logical().equals(name)); }
    public List<String> targets(String name,String env) {
        var row=match(current().rules(),name,env); return row==null ? List.of() : row.targets();
    }
    public State save(long revision,List<Rule> rules,ModelCatalog catalog) {
        validate(rules,catalog);
        var old=current(); check(old,revision);
        return swap(old,new State(revision+1,old.version(),old.rules(),List.copyOf(rules),old.history()));
    }
    public State publish(long revision, java.util.function.Consumer<List<Rule>> validate) {
        var old=current(); check(old,revision); validate.accept(old.draft());
        var history=new ArrayList<>(old.history()); history.add(old.rules());
        return swap(old,new State(revision+1,old.version()+1,old.draft(),old.draft(),List.copyOf(history)));
    }
    public State rollback(long revision,java.util.function.Consumer<List<Rule>> validate) {
        var old=current(); check(old,revision);
        if(old.history().isEmpty()) throw invalid("没有上一版路由表");
        var restore=old.history().getLast(); validate.accept(restore);
        var history=new ArrayList<>(old.history()); history.add(old.rules());
        return swap(old,new State(revision+1,old.version()+1,restore,restore,List.copyOf(history)));
    }
    static void check(State old,long revision) {
        if(old.revision()!=revision) throw invalid("路由草稿已被更新，请重新加载后再操作");
    }
    private synchronized State swap(State old,State next) {
        var before=encode(old); var after=encode(next);
        if(redis!=null) {
            Long changed=redis.eval("if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end redis.call('SET',KEYS[1],ARGV[2]) return 1",ScriptOutputType.INTEGER,new String[]{KEY},before,after);
            if(changed==null || changed!=1) throw invalid("路由草稿已被更新，请重新加载");
        } else { if(!memory.equals(before)) throw invalid("路由草稿已被更新，请重新加载"); memory=after; }
        return next;
    }
    public static void validate(List<Rule> rules,ModelCatalog catalog) {
        if(rules==null || rules.size()>100) throw invalid("路由规则最多100条");
        var unique=new HashSet<String>();
        for(var r:rules) {
            if(r.logical()==null || !r.logical().matches("^[a-z][a-z0-9-]{1,62}$") || catalog.models().containsKey(r.logical())) throw invalid("逻辑名称不合法或与实际模型重名");
            if(!Set.of("prod","staging").contains(r.env()) || !unique.add(r.logical()+":"+r.env())) throw invalid("环境只能为prod/staging，同一逻辑模型和环境只能有一条规则");
            if(r.primaryWeight()<0 || r.primaryWeight()>100 || r.grayPercent()<0 || r.grayPercent()>100 || r.minInputTokens()<0) throw invalid("权重和灰度须在0到100之间，输入阈值不能为负");
            if(r.primary()==null || r.secondary()==null || r.fallback()==null) throw invalid("主模型、次模型和降级策略不能为空");
            var all=new LinkedHashSet<>(r.targets()); all.add(r.primary()); all.add(r.secondary());
            for(String model:all) if(!catalog.models().containsKey(model) || catalog.require(model).embedding()) throw invalid("必须选择已接入的对话模型: "+model);
        }
    }
    static Gateway.GatewayException invalid(String message) { return new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM,message); }
    private static String encode(State s) { try{return JSON.writeValueAsString(s);}catch(Exception e){throw new IllegalStateException(e);} }
    private static State decode(String s) { try{return JSON.readValue(s,State.class);}catch(Exception e){throw new IllegalStateException("路由快照不可读",e);} }
}
