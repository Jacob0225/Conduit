package dev.conduit.hotload.jvm;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point. Copied into a tiny temporary agent jar and attached to
 * the running game. It only hands the {@link Instrumentation} to the game through
 * a system property (the agent class lives in the app class loader, the game's
 * code in the game layer, so a property is the simplest shared place).
 */
public final class AgentMain {
    public static final String PROPERTY = "conduit.hotload.instrumentation";

    private AgentMain() {}

    public static void agentmain(String args, Instrumentation inst) {
        System.getProperties().put(PROPERTY, inst);
    }
}
