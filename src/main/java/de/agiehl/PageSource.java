package de.agiehl;

public interface PageSource {
    String fetch() throws InterruptedException;
}
