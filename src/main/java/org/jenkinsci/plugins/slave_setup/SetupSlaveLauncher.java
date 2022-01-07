package org.jenkinsci.plugins.slave_setup;


import java.io.IOException;

import com.google.common.base.Strings;

import hudson.remoting.Channel;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DelegatingComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

/**
 * Implements the custom logic for an on-demand slave, executing scripts before connecting and after disconnecting
 */
public class SetupSlaveLauncher extends DelegatingComputerLauncher {
    private final String startScript;
    private final String stopScript;
    private final int maxLaunchAttempts;

    private volatile boolean executingStartScript;
    private volatile boolean executingStopScript;

    @DataBoundConstructor
    public SetupSlaveLauncher(ComputerLauncher launcher,
                              String startScript,
                              String stopScript,
                              String maxLaunchAttempts) {
        super(launcher);
        this.startScript = startScript;
        this.stopScript = stopScript;
        this.maxLaunchAttempts = parse(maxLaunchAttempts);
    }

    public static int parse(String p) {
        if (p == null) {
            return -1;
        }
        try {
            return Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Executes a script on the master node, with a bit of tracing.
     * @param script String script to execute.
     * @param listener TaskListener of the job.
     */
    private void execute(String script, TaskListener listener) throws IOException, InterruptedException {
        Jenkins jenkins = Jenkins.get();
        if (Strings.isNullOrEmpty(script)) {
            listener.getLogger().println("No script to be executed for this on-demand slave.");
            return;
        }

        FilePath root = jenkins.getRootPath();
        int r = Utils.multiOsExecutor(listener,script,root,null);
        if (r != 0) {
            throw new AbortException("Script failed with return code " + Integer.toString(r) + ".");
        }
    }

    /**
     * Getters for Jelly
     * @return Object startScript
     * 
     */
    public String getStartScript() {
        return startScript;
    }

    /**
     * @return Object stopScript
     */
    public String getStopScript() {
        return stopScript;
    }

    /**
     * @return Object maxLaunchAttempts
     */
    public String getMaxLaunchAttempts() {
        return String.valueOf(maxLaunchAttempts);
    }

    /**
     *  Delegated methods that plug the additional logic for on-demand slaves
     * 
     * @param computer SlaveComputer target to perform the launch.
     * @param listener Job's TaskListener 
     * 
     */
    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        final LauncherLogger logger = new LauncherLogger(listener.getLogger());

        if (!executingStartScript) {
            if (executingStopScript) {
                logger.error("Launch called while disconnect is still in progress!");
            }
            executingStopScript = false;

            try {
                logger.state("Pre-Launch Node");
                executingStartScript = true;
                execute(startScript, listener);
            } catch (Exception e) {
                logger.error("Failed to execute script '" + startScript);
                logger.stacktrace(e);
                executingStartScript = false;
                return;
            }

            logger.state("Pre-Launch Node - completed");
        } else {
            logger.error("Pre-Launch called while Pre-Launch is in progress!");
        }

        final int maxAttempts = maxLaunchAttempts <= 0 ? 1 : maxLaunchAttempts;
        for (int i = 0; i < maxAttempts; i++) {
            logger.state("Launch attempt " + String.valueOf(i+1) + " of " + String.valueOf(maxAttempts));
            try {
                super.launch(computer, listener);
                executingStartScript = false;
                return;
            } catch (Exception e) {
                logger.error("Launch failed:");
                logger.stacktrace(e);
            }
            Thread.sleep(1500);
        }
        executingStartScript = false;

        logger.state("Moving to disconnect after failed launch attempt(s)");
        afterDisconnect(computer, listener);
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        super.afterDisconnect(computer, listener);
        final LauncherLogger logger = new LauncherLogger(listener.getLogger());

        if (!executingStopScript) {
            if (executingStartScript) {
                logger.error("Disconnect called while launch is still in progress!");
            }
            executingStartScript = false;

            executingStopScript = true;
            try {
                logger.state("Post-Disconnect Node");

                final long channelCloseTimeout = System.currentTimeMillis() + 800000;
                boolean waitMessageShown = false;
                while (computer.getChannel() != null) {
                    if (!waitMessageShown) {
                        waitMessageShown = true;
                        logger.state("Waiting for channel to close...");
                    }

                    if (System.currentTimeMillis() > channelCloseTimeout) {
                        logger.state("Channel close wait timed out");
                        break;
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        logger.state("Channel closed");
                        break;
                    }
                    final Channel ch = computer.getChannel();
                    if (ch == null) {
                        logger.state("Channel closed");
                        break;
                    }
                    if (ch.isClosingOrClosed()) {
                        try {
                            Thread.sleep(2000);
                        } catch (Exception e) {
                        }
                        logger.state("Channel closed");
                        break;
                    }
                }

                execute(stopScript, listener);
                logger.state("Post-Disconnect Node - completed");
                executingStopScript = false;
            } catch (Exception e) {
                logger.error("Failed to execute script '" + stopScript + "'.");
                logger.stacktrace(e);
                executingStopScript = false;
            }
        }
        //else {
            // seems to be called twice and this message is always displayed?
//            logger.error("Post-Disconnect called while Post-Disconnect is in progress!");
//        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {
        public String getDisplayName() {
            return "Start and stop this node on-demand";
        }
    }
}

