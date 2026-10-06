package de.agiehl;

import java.net.URI;

public enum Section {
    CROWDFUNDING("crowdfunding", "Crowdfunding"),
    PRE_ORDER("pre-order", "Pre-order");

    private final String id;
    private final String label;

    Section(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String link(URI pageUrl) {
        return pageUrl.toString().split("#", 2)[0] + "#" + id;
    }
}
