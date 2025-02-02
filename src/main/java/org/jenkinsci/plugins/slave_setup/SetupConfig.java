package org.jenkinsci.plugins.slave_setup;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import hudson.util.LogTaskListener;
import hudson.model.Messages;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps track of the configuration of slave_setup execution.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class SetupConfig extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(Descriptor.class.getName());

    private List<SetupConfigItem> setupConfigItems = new ArrayList<>();

    public SetupConfig() {
        load();
    }

    public List<SetupConfigItem> getSetupConfigItems() {
        return setupConfigItems;
    }

    public void setSetupConfigItems(List<SetupConfigItem> setupConfigItems) {
        this.setupConfigItems = setupConfigItems;
    }

    /**
     * GlobalConfiguration override.
     * Begin this SetupConfig initialization binding configJson, seting up Listener and performing
     * this config execution on all activeSlaves.
     * 
     * @param req StaplerRequest from jenkins classes
     * @param json JSONObject from jenkins classes
     * 
     * @return Boolean if the config setup for all the slaves went correctly if true.
     */
    @Override
    public boolean configure(StaplerRequest req, JSONObject json) {
        req.bindJSON(this, json);
        save();

        Components.setLogger(new LogTaskListener(LOGGER, Level.ALL));

        return Components.doConfigSetups(Utils.getAllActiveSlaves());

    }

    /**
     * Get this SetupConfig class 
     * @return class SetupConfig
     */
    public static SetupConfig get() {
        return GlobalConfiguration.all().get(SetupConfig.class);
    }

    /**
     * Autocompletion string for jelly to autocomplete node label.
     * 
     * @param value String to be compared and autocompleted.
     * 
     * @return AutoCompletionCandidates for the given value checking all Jenkins instance labels.
     * 
     */
    public AutoCompletionCandidates doAutoCompleteAssignedLabelString(@QueryParameter String value) {
        AutoCompletionCandidates c = new AutoCompletionCandidates();
        Set<Label> labels = Jenkins.get().getLabels();
        List<String> queries = new AutoCompleteSeeder(value).getSeeds();

        for (String term : queries) {
            for (Label l : labels) {
                if (l.getName().startsWith(term)) {
                    c.add(l.getName());
                }
            }
        }
        return c;
    }

    public FormValidation doCheckFilesDir(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (Util.fixEmpty(value) == null) {
            return FormValidation.ok(); // no value
        }

        if (!new File(value).isDirectory()) {
            return FormValidation.error("Directory " + value + " doesn't exist");
        }

        return FormValidation.ok();
    }

    public FormValidation doCheckAssignedLabelString(@QueryParameter String value) {
        if (Util.fixEmpty(value) == null) {
            return FormValidation.ok(); // nothing typed yet
        }

        try {
            Label.parseExpression(value);
        } catch (ANTLRException e) {
            return FormValidation.error(e, "Invalid boolean expression: " + e.getMessage());
        }

        Label l = Jenkins.get().getLabel(value);

        if (l.isEmpty()) {
            for (LabelAtom a : l.listAtoms()) {
                if (a.isEmpty()) {
                    LabelAtom nearest = LabelAtom.findNearest(a.getName());
                    return FormValidation.warning("Assigned label string " + a.getName() + " didn't match, did you mean " + nearest.getDisplayName() + "?");
                }
            }
            return FormValidation.warning("Assigned label didn't match");
        }
        return FormValidation.ok();
    }

    /**
     * Utility class for taking the current input value and computing a list of
     * potential terms to match against the list of defined labels.
     */
    static class AutoCompleteSeeder {
        private final String source;

        AutoCompleteSeeder(String source) {
            this.source = source;
        }

        List<String> getSeeds() {
            ArrayList<String> terms = new ArrayList<>();
            boolean trailingQuote = source.endsWith("\"");
            boolean leadingQuote = source.startsWith("\"");
            boolean trailingSpace = source.endsWith(" ");

            if (trailingQuote || (trailingSpace && !leadingQuote)) {
                terms.add("");
            } else {
                if (leadingQuote) {
                    int quote = source.lastIndexOf('"');
                    if (quote == 0) {
                        terms.add(source.substring(1));
                    } else {
                        terms.add("");
                    }
                } else {
                    int space = source.lastIndexOf(' ');
                    if (space > -1) {
                        terms.add(source.substring(space + 1));
                    } else {
                        terms.add(source);
                    }
                }
            }

            return terms;
        }
    }
}
