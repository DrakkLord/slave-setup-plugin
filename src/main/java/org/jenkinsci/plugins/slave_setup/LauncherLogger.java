package org.jenkinsci.plugins.slave_setup;

import java.io.PrintStream;

public class LauncherLogger {
    private final PrintStream target;

    public LauncherLogger(PrintStream target) {
        this.target = target;
    }

    void state(String text) {
        target.println("@" + text);
    }

    void error(String text) {
        target.println();
        target.println(">> " + text);
        target.println();
    }

    void stacktrace(Object obj) {
        target.println(obj);
    }
}
