package dev.conduit.hotload.jvm;

import com.sun.tools.attach.VirtualMachine;

/**
 * Runs in a short-lived separate JVM: attaches to the game process and loads the
 * agent jar. (A JVM may not attach to itself unless started with
 * {@code -Djdk.attach.allowAttachSelf=true}, which launchers don't set.)
 */
public final class Attacher {
    private Attacher() {}

    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try {
            vm.loadAgent(args[1]);
        } finally {
            vm.detach();
        }
    }
}
