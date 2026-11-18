package redero.abyssaldepths.cinema;

/**
 * An entity that can act in a cutscene: told to play one of its model's animations from a game tick on (the client
 * side copy the cutscene spawned). Its renderer plays {@link #acted()} over whatever it would play itself.
 */
public interface CutsceneActor {
    /** Plays a named animation from a game tick on ("" stops it). */
    void act(String animation, long fromTick);

    /** The animation it was told to play and since when (game tick), or null. */
    Acting acted();

    /** Set up from the cutscene's actor data (e.g. a Shadeborn's role); nothing by default. */
    default void setUpActor(com.google.gson.JsonObject data) {
    }

    record Acting(String animation, long from) {
    }
}
