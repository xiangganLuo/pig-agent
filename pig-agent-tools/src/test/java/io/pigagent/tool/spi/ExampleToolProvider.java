package io.pigagent.tool.spi;

/** Test-only provider declared in the test-scoped services file; yields {@link ExampleTool}. */
public final class ExampleToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new ExampleTool();
    }
}
