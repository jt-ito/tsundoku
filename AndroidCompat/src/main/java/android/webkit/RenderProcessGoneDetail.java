package android.webkit;

/**
 * Stub of the Android class. The android-jar artifact served by JitPack lost it, which broke the build on CI.
 * It has the same API as the real one, so it is harmless where the jar still has it.
 */
public abstract class RenderProcessGoneDetail {
    public RenderProcessGoneDetail() {}

    public abstract boolean didCrash();

    public abstract int rendererPriorityAtExit();
}
