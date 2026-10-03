package app;

import com.keel.starter.annotation.KeelAgent;
import com.keel.starter.annotation.KeelEntry;
import com.keel.starter.context.KeelContext;
import java.util.Map;

@KeelAgent(manifest = "classpath:{{name_aux}}.yaml")
public class AuxAgent {
    @KeelEntry
    public Object run(Map<String, Object> request, KeelContext ctx) {
        return ctx.finish(ctx.agent());
    }
}
