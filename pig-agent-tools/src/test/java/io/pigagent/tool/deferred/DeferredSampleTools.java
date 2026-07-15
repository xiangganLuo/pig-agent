package io.pigagent.tool.deferred;

import io.agentscope.core.tool.Tool;

/** Fixture with several {@code @Tool} methods so gate deferral can be verified on a real Toolkit. */
public final class DeferredSampleTools {

    @Tool(name = "getWeather", description = "Get the current weather forecast for a city.")
    public String getWeather() {
        return "sunny";
    }

    @Tool(name = "sendEmail", description = "Send an email message to a recipient.")
    public String sendEmail() {
        return "sent";
    }

    @Tool(name = "readFile", description = "Read a file from disk.")
    public String readFile() {
        return "content";
    }
}
