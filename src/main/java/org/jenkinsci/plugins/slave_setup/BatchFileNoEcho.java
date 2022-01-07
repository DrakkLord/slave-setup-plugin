package org.jenkinsci.plugins.slave_setup;

import hudson.FilePath;
import hudson.tasks.BatchFile;

public class BatchFileNoEcho extends BatchFile {
    public BatchFileNoEcho(String command) {
        super(command);
    }

    @Override
    public String[] buildCommandLine(FilePath script) {
        return new String[]{"cmd", "/Q", "/C", "call", script.getRemote()};
    }
}
