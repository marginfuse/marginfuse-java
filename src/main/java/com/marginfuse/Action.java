package com.marginfuse;

/** A verdict. Enforce on this alone. */
public enum Action {
    ALLOW("allow"),
    DOWNGRADE("downgrade"),
    TOPUP_REQUIRED("topup_required"),
    BLOCK("block");

    private final String wire;

    Action(String wire) { this.wire = wire; }

    public String wire() { return wire; }

    /** Unknown values from a newer server resolve to ALLOW: an action this
     *  version cannot enforce must never silently become a block. */
    public static Action fromWire(String value) {
        for (Action a : values()) {
            if (a.wire.equals(value)) return a;
        }
        return ALLOW;
    }
}
