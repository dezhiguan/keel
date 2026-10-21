package com.keel.llm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Admin operations and offline replay, sharing the execution table. */
final class LogicalRouting {
    private final Gateway gateway;
    private final ModelCatalog catalog;
    private final KeyDirectory keys;
    private final RouteTable table;
    private final RoutingJournal journal;
    LogicalRouting(Gateway gateway,ModelCatalog catalog,KeyDirectory keys,RouteTable table,RoutingJournal journal) {
        this.gateway=gateway;this.catalog=catalog;this.keys=keys;this.table=table;this.journal=journal;
    }
    record Gap(String alias,String logical,List<String> models) {}
    List<Gap> gaps(List<RouteTable.Rule> rules) {
        var result=new ArrayList<Gap>();
        for(var key:keys.list()) {
            if(key.blocked())continue;
            for(var r:rules) {
                if(!key.models().contains(r.logical()) || RouteTable.match(List.of(r),r.logical(),env(key.alias()))==null)continue;
                var missing=r.targets().stream().filter(m -> !gateway.covers(key,m)).toList();
                if(!missing.isEmpty())result.add(new Gap(key.alias(),r.logical(),missing));
            }
        }
        return result;
    }
    List<String> references(List<RouteTable.Rule> rules) {
        var result=new ArrayList<String>();
        for(var key:keys.list())if(!key.blocked())for(var old:table.current().rules()) {
            if(key.models().contains(old.logical()) && RouteTable.match(List.of(old),old.logical(),env(key.alias()))!=null
                    && RouteTable.match(rules,old.logical(),env(key.alias()))==null) result.add(key.alias()+" 引用 "+old.logical());
        }
        return result.stream().distinct().toList();
    }
    Map<String,Object> view() {
        var s=table.current();
        return Map.of("revision",s.revision(),"version",s.version(),"rules",s.rules(),"draft",s.draft(),
                "gaps",gaps(s.draft()),"references",references(s.draft()),"canRollback",!s.history().isEmpty(),
                "models",catalog.models().values().stream().filter(m->!m.embedding()).map(ModelCatalog.Model::name).sorted().toList());
    }
    Map<String,Object> save(long revision,List<RouteTable.Rule> rules) {table.save(revision,rules,catalog);return view();}
    private void validatePublication(List<RouteTable.Rule> rules) {
        RouteTable.validate(rules,catalog);
        var refs=references(rules);if(!refs.isEmpty())throw RouteTable.invalid("逻辑模型仍被引用，不能删除: "+String.join("；",refs));
        var missing=gaps(rules);if(!missing.isEmpty())throw RouteTable.invalid("发布前要补授权: "+missing.stream().map(g->g.alias()+" 缺 "+String.join(",",g.models())).reduce((a,b)->a+"；"+b).orElse(""));
    }
    Map<String,Object> publish(long revision) {table.publish(revision,this::validatePublication);return view();}
    Map<String,Object> rollback(long revision) {table.rollback(revision,this::validatePublication);return view();}
    Map<String,Object> grant(long revision) {
        RouteTable.check(table.current(),revision);
        for(var gap:gaps(table.current().draft()))gateway.grantRouteModels(gap.alias(),gap.models());
        RouteTable.check(table.current(),revision);
        return view();
    }
    Map<String,Object> replay(long revision,List<Gateway.Spend> bills) {
        var s=table.current();RouteTable.check(s,revision);
        var billByRequest=new HashMap<String,Gateway.Spend>();
        for(var b:bills)if("SETTLED".equals(b.status()))billByRequest.put(b.requestId(),b);
        var live=new TreeMap<String,Integer>();var draft=new TreeMap<String,Integer>();
        BigDecimal liveCost=BigDecimal.ZERO,draftCost=BigDecimal.ZERO;
        int missing=0,unmatched=0,priced=0;var seen=new HashSet<String>();
        var entries=journal.recent();
        var keyMap=new HashMap<String,Gateway.VirtualKey>();keys.list().forEach(k->keyMap.put(k.alias(),k));
        var coverage=new HashMap<String,Boolean>();
        for(var e:entries) {
            if(!seen.add(e.id()))continue;
            var a=RouteTable.match(s.rules(),e.logical(),env(e.alias()));
            var b=RouteTable.match(s.draft(),e.logical(),env(e.alias()));
            if(a==null || b==null){unmatched++;continue;}
            var actual=billByRequest.get(e.id());
            int input=e.inputEstimate();
            if(input<a.minInputTokens()||input<b.minInputTokens()){unmatched++;continue;}
            String before=a.select(e.id()),after=b.select(e.id());
            live.merge(before,1,Integer::sum);draft.merge(after,1,Integer::sum);
            var key=keyMap.get(e.alias());if(key==null || !coverage.computeIfAbsent(e.alias()+":"+after,ignored->gateway.covers(key,after)))missing++;
            if(actual!=null && actual.inputTokens()>=0 && actual.outputTokens()>=0 && catalog.models().containsKey(before) && catalog.models().containsKey(after)) {
                liveCost=liveCost.add(cost(before,actual));draftCost=draftCost.add(cost(after,actual));priced++;
            }
        }
        long old=bills.stream().filter(b->b.ts().isAfter(Instant.now().minusSeconds(86400))&&!seen.contains(b.requestId())).map(Gateway.Spend::requestId).distinct().count();
        return Map.ofEntries(Map.entry("requests",seen.size()),Map.entry("published",live),Map.entry("draft",draft),Map.entry("missingAuthorization",missing),
                Map.entry("unmatched",unmatched),Map.entry("pricedRequests",priced),Map.entry("publishedEstimatedCny",liveCost),Map.entry("draftEstimatedCny",draftCost),
                Map.entry("legacyRequests",old),Map.entry("recordLimit",100000),Map.entry("windowHours",24),Map.entry("version",s.version()),Map.entry("revision",s.revision()));
    }
    private BigDecimal cost(String name,Gateway.Spend bill) {
        var model=catalog.require(name);
        int cached=Math.max(0,Math.min(bill.inputTokens(),bill.cacheTokens()));
        return model.inputCnyPerToken().multiply(BigDecimal.valueOf(bill.inputTokens()-cached))
                .add((model.cacheReadCnyPerToken()==null?model.inputCnyPerToken():model.cacheReadCnyPerToken()).multiply(BigDecimal.valueOf(cached)))
                .add(model.outputCnyPerToken().multiply(BigDecimal.valueOf(bill.outputTokens())));
    }
    static String env(String alias){return alias.substring(alias.lastIndexOf('-')+1);}
}
