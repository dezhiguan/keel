package app;

import com.keel.starter.annotation.KeelAgent;
import com.keel.starter.annotation.KeelEntry;
import com.keel.starter.context.KeelContext;
import java.util.Map;

@KeelAgent(manifest = "classpath:{{name}}.yaml")
public class PrimaryAgent {
    @KeelEntry
    public Object run(Map<String, Object> request, KeelContext ctx) {
        ctx.step("greet");
        return ctx.finish(ctx.agent());
    }
}
