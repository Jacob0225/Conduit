package com.jacob0225.conduit.client.compat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Optional API boundary. No Hotwire implementation or bytecode is bundled. */
public final class HotwireBridge {
    private static Class<?> apiType() throws ReflectiveOperationException {
        return Class.forName("dev.moon.hotwire.api.HotwireApi");
    }

    private static Object api() throws ReflectiveOperationException {
        return apiType().getMethod("getInstance").invoke(null);
    }

    public static boolean available() {
        try { api(); return true; }
        catch (ReflectiveOperationException | LinkageError e) { return false; }
    }

    /** Show upstream's required prompt; the player must accept it before retrying. */
    public static boolean promptAccepted() throws ReflectiveOperationException {
        Object api = api();
        apiType().getMethod("showPrompt").invoke(api);
        return apiType().getMethod("findOpeningPrompt").invoke(api) == null;
    }

    public static CompletableFuture<Plan> plan(List<Path> jars) throws ReflectiveOperationException {
        return plan(jars, List.of(), List.of());
    }

    public record RunningMod(String id, String version, boolean on) {}

    public static List<RunningMod> runningMods() throws ReflectiveOperationException {
        Class<?> type = Class.forName("dev.moon.hotwire.api.ModState");
        List<RunningMod> result = new java.util.ArrayList<>();
        for (Object mod : (List<?>) apiType().getMethod("getMods").invoke(api())) {
            result.add(new RunningMod((String) type.getMethod("id").invoke(mod),
                    (String) type.getMethod("version").invoke(mod), (boolean) type.getMethod("on").invoke(mod)));
        }
        return List.copyOf(result);
    }

    public static CompletableFuture<Plan> plan(List<Path> jars, List<String> off, List<String> on)
            throws ReflectiveOperationException {
        Class<?> requestType = Class.forName("dev.moon.hotwire.api.ChangeRequest");
        Object request = requestType.getConstructor().newInstance();
        request = requestType.getMethod("tag", String.class).invoke(request, "conduit-hotwire");
        request = requestType.getMethod("off", java.util.Collection.class).invoke(request, off);
        request = requestType.getMethod("on", java.util.Collection.class).invoke(request, on);
        for (Path jar : jars) request = requestType.getMethod("jar", Path.class).invoke(request, jar);
        Object future = apiType().getMethod("planChange", requestType).invoke(api(), request);
        return ((CompletableFuture<?>) future).thenApply(Plan::new);
    }

    public static final class Plan {
        private final Object delegate;
        private Plan(Object delegate) { this.delegate = delegate; }
        private Object call(String name) throws ReflectiveOperationException {
            Class<?> type = Class.forName("dev.moon.hotwire.api.ChangePlan");
            return type.getMethod(name).invoke(delegate);
        }
        public boolean canApply() throws ReflectiveOperationException {
            return (boolean) call("canApply") && !(boolean) call("needsReopen");
        }
        public String description() throws ReflectiveOperationException {
            return String.join("\n", ((List<?>) call("getLines")).stream().map(Object::toString).toList());
        }
        public void cancel() throws ReflectiveOperationException { call("cancel"); }
        public CompletableFuture<Boolean> accept() throws ReflectiveOperationException {
            return ((CompletableFuture<?>) call("accept")).thenApply(result -> {
                try {
                    Class<?> type = Class.forName("dev.moon.hotwire.api.SwitchResult");
                    return (boolean) type.getMethod("isDone").invoke(result)
                            && ((List<?>) type.getMethod("atNextStart").invoke(result)).isEmpty();
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            });
        }
    }

    private HotwireBridge() {}
}
