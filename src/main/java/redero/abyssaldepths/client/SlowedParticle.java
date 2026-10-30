package redero.abyssaldepths.client;

/**
 * A particle that can run slower (Interlude): every particle is one, through
 * {@code InterludeParticleMixin}; the particle engine calls these around each particle's tick.
 */
public interface SlowedParticle {
    /** Before the tick: true if it is skipped (the particle takes the rest of its last step instead). */
    boolean abyssaldepths$skip();

    /** After a real tick: only part of its step is taken now. */
    void abyssaldepths$after();
}
