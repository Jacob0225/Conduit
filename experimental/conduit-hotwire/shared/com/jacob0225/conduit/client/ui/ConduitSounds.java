package com.jacob0225.conduit.client.ui;

import com.jacob0225.conduit.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Layered UI feedback sounds for Conduit's screens, built from vanilla sound
 * events so no audio assets ship with the mod.
 *
 * <p>Every method is a no-op when "UI sounds" is turned off in Conduit's
 * settings, except {@link #press} which then falls back to the plain vanilla
 * button click so buttons never go silent.
 *
 * <p>Same source compiles against 1.21.1 and 26.x: only APIs present in both
 * ({@code SimpleSoundInstance.forUI}, {@code SoundManager.play/playDelayed}).
 */
public final class ConduitSounds {

    /** What a button press should sound like. */
    public enum Press { PRIMARY, NORMAL, BACK, WARNING }

    /** C major pentatonic, in semitones from the base note: rising "progress" steps. */
    private static final int[] STEPS = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24};

    private static long lastHoverMs;

    private ConduitSounds() {}

    private static boolean enabled() {
        return ClientConfig.get().uiSounds();
    }

    private static SoundManager sounds() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : mc.getSoundManager();
    }

    /** Conduit's own volume slider, applied on top of Minecraft's master volume. */
    private static float scale(float volume) {
        return volume * ClientConfig.get().soundVolume() / 100.0F;
    }

    private static void play(SoundEvent event, float pitch, float volume) {
        SoundManager manager = sounds();
        float v = scale(volume);
        if (manager != null && v > 0.001F) manager.play(SimpleSoundInstance.forUI(event, clampPitch(pitch), v));
    }

    private static void play(Holder<SoundEvent> event, float pitch, float volume) {
        play(event.value(), pitch, volume);
    }

    private static void later(SoundEvent event, float pitch, float volume, int ticks) {
        SoundManager manager = sounds();
        float v = scale(volume);
        if (manager != null && v > 0.001F) {
            manager.playDelayed(SimpleSoundInstance.forUI(event, clampPitch(pitch), v), ticks);
        }
    }

    private static void later(Holder<SoundEvent> event, float pitch, float volume, int ticks) {
        later(event.value(), pitch, volume, ticks);
    }

    private static float clampPitch(float pitch) {
        return Math.max(0.5F, Math.min(2.0F, pitch));
    }

    private static float jitter(float pitch, float amount) {
        return pitch + (ThreadLocalRandom.current().nextFloat() * 2.0F - 1.0F) * amount;
    }

    /** Pitch multiplier for a note {@code semitones} above/below the base. */
    private static float note(int semitones) {
        return (float) Math.pow(2.0D, semitones / 12.0D);
    }

    /** Button press: a crisp click with a little texture on top. */
    public static void press(Press kind) {
        if (!enabled()) {
            SoundManager manager = sounds();
            if (manager != null) manager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return;
        }
        switch (kind) {
            case PRIMARY -> {
                play(SoundEvents.UI_BUTTON_CLICK, jitter(1.08F, 0.03F), 0.55F);
                play(SoundEvents.NOTE_BLOCK_HAT, 1.7F, 0.30F);
                later(SoundEvents.AMETHYST_BLOCK_CHIME, jitter(1.35F, 0.05F), 0.85F, 1);
            }
            case BACK -> {
                play(SoundEvents.UI_BUTTON_CLICK, jitter(0.82F, 0.03F), 0.5F);
                play(SoundEvents.NOTE_BLOCK_HAT, 1.2F, 0.22F);
            }
            case WARNING -> {
                play(SoundEvents.UI_BUTTON_CLICK, jitter(0.95F, 0.03F), 0.55F);
                play(SoundEvents.NOTE_BLOCK_BASS, 1.0F, 0.35F);
            }
            default -> {
                play(SoundEvents.UI_BUTTON_CLICK, jitter(1.0F, 0.04F), 0.55F);
                play(SoundEvents.NOTE_BLOCK_HAT, 1.5F, 0.25F);
            }
        }
    }

    /** Tiny tick when the pointer first lands on an enabled control. */
    public static void hover() {
        if (!enabled() || !ClientConfig.get().hoverSounds()) return;
        long now = System.currentTimeMillis();
        if (now - lastHoverMs < 45L) return; // sweeping across a row of buttons shouldn't buzz
        lastHoverMs = now;
        play(SoundEvents.NOTE_BLOCK_HAT, jitter(1.95F, 0.03F), 0.10F);
    }

    /** A switch flipping on or off. */
    public static void toggle(boolean on) {
        if (!enabled()) return;
        play(on ? SoundEvents.COPPER_BULB_TURN_ON : SoundEvents.COPPER_BULB_TURN_OFF, on ? 1.25F : 1.0F, 0.6F);
        play(SoundEvents.UI_BUTTON_CLICK, on ? 1.2F : 0.9F, 0.35F);
    }

    /** A Conduit screen slides in. */
    public static void open() {
        if (!enabled()) return;
        play(SoundEvents.UI_TOAST_IN, 1.1F, 0.9F);
    }

    /** Scrolling a list by one row. */
    public static void scroll() {
        if (!enabled()) return;
        play(SoundEvents.NOTE_BLOCK_HAT, jitter(1.35F, 0.05F), 0.12F);
    }

    /** Work starts (downloads or a Hotwire plan). */
    public static void start() {
        if (!enabled()) return;
        play(SoundEvents.CONDUIT_AMBIENT_SHORT, 1.2F, 0.8F);
        play(SoundEvents.NOTE_BLOCK_CHIME, note(-5), 0.35F);
    }

    /** One item finished; {@code index} climbs a pentatonic scale so a batch "plays a tune". */
    public static void step(int index) {
        if (!enabled()) return;
        int semis = STEPS[Math.floorMod(index, STEPS.length)] - 12;
        play(SoundEvents.NOTE_BLOCK_CHIME, note(semis), 0.45F);
        play(SoundEvents.NOTE_BLOCK_HAT, 1.8F, 0.12F);
    }

    /** One item failed. */
    public static void stepFailed() {
        if (!enabled()) return;
        play(SoundEvents.NOTE_BLOCK_BASS, note(-7), 0.55F);
        play(SoundEvents.NOTE_BLOCK_DIDGERIDOO, note(-12), 0.25F);
    }

    /** Everything worked: the Conduit powers up, followed by a little sparkle. */
    public static void success() {
        if (!enabled()) return;
        play(SoundEvents.CONDUIT_ACTIVATE, 1.0F, 0.75F);
        later(SoundEvents.NOTE_BLOCK_CHIME, note(0), 0.45F, 2);
        later(SoundEvents.NOTE_BLOCK_CHIME, note(4), 0.45F, 4);
        later(SoundEvents.NOTE_BLOCK_CHIME, note(7), 0.5F, 6);
        later(SoundEvents.NOTE_BLOCK_CHIME, note(12), 0.55F, 9);
    }

    /** Something needs attention. */
    public static void failure() {
        if (!enabled()) return;
        play(SoundEvents.CONDUIT_DEACTIVATE, 1.0F, 0.7F);
        later(SoundEvents.NOTE_BLOCK_BASS, note(-5), 0.5F, 2);
        later(SoundEvents.NOTE_BLOCK_BASS, note(-9), 0.5F, 6);
    }

    /** A softer heads-up (e.g. live loading fell back to a restart). */
    public static void notice() {
        if (!enabled()) return;
        play(SoundEvents.NOTE_BLOCK_PLING, note(2), 0.4F);
        later(SoundEvents.NOTE_BLOCK_PLING, note(-3), 0.4F, 3);
    }

    /** Switching settings tabs: a soft page flick. */
    public static void tab(int index) {
        if (!enabled()) return;
        play(SoundEvents.BOOK_PAGE_TURN, 1.25F + index * 0.08F, 0.55F);
        play(SoundEvents.NOTE_BLOCK_HAT, 1.6F + index * 0.1F, 0.18F);
    }

    /** A slider moved to {@code percent}: pitch follows the value, like a dial. */
    public static void slider(int percent) {
        if (!enabled()) return;
        play(SoundEvents.NOTE_BLOCK_HAT, 0.8F + percent / 100.0F, 0.22F);
        play(SoundEvents.NOTE_BLOCK_CHIME, note(Math.round(percent / 100.0F * 24.0F) - 12), 0.18F);
    }

    /** Cycling an option (the accent theme); each choice has its own chord. */
    public static void cycle(int index) {
        if (!enabled()) return;
        int[] roots = {0, 5, 7, 3};
        int root = roots[Math.floorMod(index, roots.length)];
        play(SoundEvents.AMETHYST_BLOCK_CHIME, note(root), 0.9F);
        later(SoundEvents.NOTE_BLOCK_BELL, note(root), 0.35F, 1);
        later(SoundEvents.NOTE_BLOCK_BELL, note(root + 4), 0.3F, 3);
    }

    /** The title-screen gear starts to turn under the pointer. */
    public static void gearHover() {
        if (!enabled() || !ClientConfig.get().hoverSounds()) return;
        play(SoundEvents.UI_LOOM_SELECT_PATTERN, 1.4F, 0.45F);
    }

    /** The gear is clicked: it whirs up and the settings panel zooms open. */
    public static void gearOpen() {
        if (!enabled()) return;
        play(SoundEvents.UI_BUTTON_CLICK, 1.15F, 0.5F);
        play(SoundEvents.CONDUIT_AMBIENT_SHORT, 1.5F, 0.8F);
        later(SoundEvents.UI_TOAST_IN, 1.3F, 0.9F, 2);
        later(SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F, 0.8F, 5);
    }

    /** A Conduit screen closes. */
    public static void close() {
        if (!enabled()) return;
        play(SoundEvents.UI_TOAST_OUT, 1.1F, 0.8F);
    }

    /** Clicking the Conduit emblem: a playful whoosh and sparkle. */
    public static void emblem() {
        if (!enabled()) return;
        play(SoundEvents.CONDUIT_ATTACK_TARGET, 1.6F, 0.35F);
        play(SoundEvents.AMETHYST_CLUSTER_HIT, 1.2F, 0.8F);
        later(SoundEvents.NOTE_BLOCK_CHIME, note(7), 0.4F, 2);
        later(SoundEvents.NOTE_BLOCK_CHIME, note(12), 0.4F, 4);
    }

    /** A demo of the success jingle for the settings screen's test button. */
    public static void preview() {
        if (!enabled()) return;
        success();
    }

    /** A page of information appears (the Hotwire plan). */
    public static void page() {
        if (!enabled()) return;
        play(SoundEvents.BOOK_PAGE_TURN, 1.1F, 0.9F);
    }
}
