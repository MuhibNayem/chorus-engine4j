package com.chorus.engine.sample.claude;

import com.chorus.engine.llm.ToolDefinition;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

final class ToolWiring {

    private ToolWiring() {}

    static List<ToolDefinition> toToolDefinitions(ToolRegistry registry) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool tool : registry.allTools()) {
            defs.add(ToolDefinition.builder(tool.name(), tool.description())
                    .parametersSchema(tool.parametersSchema())
                    .required(true)
                    .build());
        }
        return defs;
    }
}
