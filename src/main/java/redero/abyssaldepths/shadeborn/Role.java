package redero.abyssaldepths.shadeborn;

/**
 * What a Shadeborn does for the player (DESIGN.md 5.2.1). The order is the id kept in saves: only ever add at the end.
 */
public enum Role {
    KEEPER("keeper"), TRADER("trader"), SMITH("smith"), WEAVER("weaver"), LAMP_KEEPER("lamp_keeper"), FORAGER("forager"),
    MENDER("mender"), SEER("seer"), PATHFINDER("pathfinder"), DELVER("delver"), STORMWATCHER("stormwatcher"), WATCHER("watcher"),
    WANDERER("wanderer"), MOURNER("mourner"), YOUNG("young"), OUTCAST("outcast"), RAIDER("raider");

    public final String id;

    Role(String id) {
        this.id = id;
    }

    public static Role of(int i) {
        final Role[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, i))];
    }

    /** Whether they fight: guards, raiders. */
    public boolean fights() {
        return this == WATCHER || this == RAIDER;
    }

    /** Whether they trade (a trade screen from Tolerated on). */
    public boolean trades() {
        return switch (this) {
            case TRADER, SMITH, WEAVER, LAMP_KEEPER, FORAGER, MENDER, SEER, PATHFINDER, DELVER, STORMWATCHER, WANDERER, OUTCAST -> true;
            default -> false;
        };
    }
}
