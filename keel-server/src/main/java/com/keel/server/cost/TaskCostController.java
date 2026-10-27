package com.keel.server.cost;
import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class TaskCostController {
    private final TaskCostService costs;private final CostPolicies policies;
    public TaskCostController(TaskCostService costs,CostPolicies policies){this.costs=costs;this.policies=policies;}
    @GetMapping("/api/v1/agents/{name}/cost-control")
    public R<?> policy(@PathVariable String name,@RequestParam String env){
        var p=TaskCostService.principal();if("SERVICE".equals(p.mode()))TaskCostService.service(name);
        return R.ok(Map.of("policy",costs.policy(name,env),"coverage",costs.supported(name)?"supported-runtime":"unsupported-or-unavailable","version",1));
    }
    @PostMapping("/api/v1/agents/{name}/cost-control/tasks")
    public R<?> open(@PathVariable String name,@RequestBody JsonNode b){
        if(b.size()>3 || !b.path("inputChars").isIntegralNumber() || b.path("inputChars").asInt()<0 || b.path("inputChars").asInt()>10000000)throw TaskCostService.denied();
        b.fieldNames().forEachRemaining(k->{if(!java.util.Set.of("env","inputChars","evaluationRun").contains(k))throw TaskCostService.denied();});
        return R.ok(costs.serviceOpen(name,b.path("env").asText(),b.path("inputChars").asInt(),b.path("evaluationRun").asText("")));
    }
    @GetMapping("/api/v1/agents/{name}/cost-control/tasks")
    public R<?> list(@PathVariable String name,@RequestParam String env){return R.ok(Map.of("items",costs.list(name,env)));}
    @GetMapping("/api/v1/agents/{name}/cost-control/tasks/{id}")
    public R<?> inspect(@PathVariable String name,@PathVariable String id,@RequestParam String env){return R.ok(costs.inspect(name,env,id));}
    @PostMapping("/api/v1/agents/{name}/cost-control/tasks/{id}/finish")
    public R<?> finish(@PathVariable String name,@PathVariable String id,@RequestBody JsonNode b){
        if(!id.equals(b.path("taskId").asText()))throw TaskCostService.denied();costs.serviceFinish(name,b.path("env").asText(),b);return R.ok(Map.of("accepted",true));
    }
    @PostMapping("/api/v1/agents/{name}/cost-control/tasks/{id}/bind")
    public R<?> bind(@PathVariable String name,@PathVariable String id,@RequestBody JsonNode b){
        if(!id.equals(b.path("taskId").asText()))throw TaskCostService.denied();costs.serviceBind(name,b.path("env").asText(),b);return R.ok(Map.of("accepted",true));
    }
    @GetMapping("/api/v1/cost-control/policies")
    public R<?> history(){admin();return R.ok(Map.of("items",policies.history()));}
    @PostMapping("/api/v1/cost-control/policies")
    @com.keel.server.common.Audited
    public R<?> publish(@RequestBody JsonNode b){
        var p=admin();
        var targets=costs.affected(b);
        if("enforce".equals(b.path("config").path("mode").asText()))
            for(var target:targets)if(!costs.supported(target.get("name").toString()))throw CostPolicies.conflict();
        var result=policies.publish(b,p.username());
        for(var target:targets) {
            String name=target.get("name").toString(),env=target.get("env").toString();
            costs.requireContext(name+"-"+env,costs.policy(name,env));
        }
        return R.ok(result);
    }
    private static com.keel.server.auth.ConsolePrincipal admin(){var p=TaskCostService.principal();if(!"ADMIN".equals(p.platformRole())||p.readOnly())throw TaskCostService.denied();return p;}
}
