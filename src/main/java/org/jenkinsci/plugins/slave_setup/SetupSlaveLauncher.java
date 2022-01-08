package org.jenkinsci.plugins.slave_setup;


import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Strings;

import hudson.remoting.Channel;
import hudson.slaves.OfflineCause;
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
    private volatile boolean isBeforeDisconnect;
    private final AtomicInteger currentLaunchAttempt = new AtomicInteger();
    private volatile RelaunchListener relaunchListener;

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
            currentLaunchAttempt.set(0);
            if (executingStopScript) {
                logger.error("Launch called while disconnect is still in progress! ( launch will proceed )");
            }
            executingStopScript = false;
            isBeforeDisconnect = false;

            try {
                logger.state("Pre-Launch Node");
                executingStartScript = true;
                execute(startScript, listener);
            } catch (Exception e) {
                logger.error("Failed to execute script '" + startScript + "'");
                logger.stacktrace(e);
                executingStartScript = false;
                return;
            }

            logger.state("Pre-Launch Node - completed");
        } else {
            logger.error("Pre-Launch called while Pre-Launch is in progress!");
        }

        final Channel computerChannel = computer.getChannel();
        if (computerChannel != null) {
            relaunchListener = new RelaunchListener(computer, listener, getMaxLaunchAttemptsLimited());
            computerChannel.addListener(relaunchListener);
        }

        try {
            tryLaunch(computer, listener);
            executingStartScript = false;
            return;
        } catch (LaunchFailedException e) {
            logger.error("Failed to launch");
        }
        executingStartScript = false;

        logger.state("Moving to disconnect after failed launch attempt(s)");
        computer.disconnect(new OfflineCause.LaunchFailed());
        afterDisconnect(computer, listener);
    }

    private void tryLaunch(SlaveComputer computer, TaskListener listener) throws LaunchFailedException {
        final LauncherLogger logger = new LauncherLogger(listener.getLogger());
        if (currentLaunchAttempt.get() < getMaxLaunchAttemptsLimited()) {
            currentLaunchAttempt.incrementAndGet();

            logger.state("Launch attempt " + String.valueOf(currentLaunchAttempt.get() +1) + " of " + String.valueOf(getMaxLaunchAttemptsLimited()));
            try {
                super.launch(computer, listener);
                executingStartScript = false;
                return;
            } catch (Exception e) {
                logger.error("Launch failed:");
                logger.stacktrace(e);
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        throw new LaunchFailedException();
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        super.beforeDisconnect(computer, listener);
        isBeforeDisconnect = true;

        // cleanup relaunch listener
        final Channel computerChannel = computer.getChannel();
        if (computerChannel != null) {
            final RelaunchListener localRelaunchListener = relaunchListener;
            if (localRelaunchListener != null) {
                relaunchListener = null;
                computerChannel.removeListener(localRelaunchListener);
            }
        }
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        super.afterDisconnect(computer, listener);
        final LauncherLogger logger = new LauncherLogger(listener.getLogger());

        if (!executingStopScript) {
            if (executingStartScript) {
                logger.error("Disconnect called while launch is still in progress! ( disconnect will proceed )");
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

    class RelaunchListener extends Channel.Listener {
        private final SlaveComputer computer;
        private final TaskListener listener;
        private final int maxAttempts;

        RelaunchListener(SlaveComputer computer, TaskListener listener, int maxAttempts) {
            this.computer = computer;
            this.listener = listener;
            this.maxAttempts = maxAttempts;
        }

        @Override
        public void onClosed(Channel channel, IOException cause) {
            super.onClosed(channel, cause);
            if (!executingStopScript && !isBeforeDisconnect && currentLaunchAttempt.get() < maxAttempts) {
                executingStartScript = true;
                tryLaunch(computer, listener);
            }
        }
    }

    private int getMaxLaunchAttemptsLimited() {
        return maxLaunchAttempts <= 0 ? 1 : maxLaunchAttempts;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {
        public String getDisplayName() {
            return "Start and stop this node on-demand";
        }
    }
}

