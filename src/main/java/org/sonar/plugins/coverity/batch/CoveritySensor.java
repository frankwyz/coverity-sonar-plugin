/*
 * Coverity Sonar Plugin
 * Copyright (c) 2017 Coverity, Inc
 * support@coverity.com
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html.
 */

package org.sonar.plugins.coverity.batch;

import com.coverity.ws.v9.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.CoreProperties;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultTextPointer;
import org.sonar.api.batch.fs.internal.DefaultTextRange;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.config.Settings;
import org.sonar.plugins.coverity.CoverityPlugin;
import org.sonar.plugins.coverity.base.CoverityPluginMetrics;
import org.sonar.plugins.coverity.util.CoverityUtil;
import org.sonar.plugins.coverity.ws.CIMClient;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.sonar.plugins.coverity.util.CoverityUtil.createURL;

public class CoveritySensor implements Sensor {
    private static final Logger LOG = LoggerFactory.getLogger(CoveritySensor.class);

    private final String HIGH = "High";
    private final String MEDIUM = "Medium";
    private final String LOW = "Low";

    private int totalDefects = 0;
    private int highImpactDefects = 0;
    private int mediumImpactDefects = 0;
    private int lowImpactDefects = 0;

    private String platform;

    public CoveritySensor() {
        platform = System.getProperty("os.name");
    }

    @Override
    public void describe(SensorDescriptor descriptor) {
        descriptor.name(this.toString())
                .createIssuesForRuleRepositories(
                        // todo: extract the list of langauges/repositories
                        CoverityPlugin.REPOSITORY_KEY + "-java",
                        CoverityPlugin.REPOSITORY_KEY + "-cs",
                        CoverityPlugin.REPOSITORY_KEY + "-c",
                        CoverityPlugin.REPOSITORY_KEY + "-cpp",
                        CoverityPlugin.REPOSITORY_KEY + "-c++")
                .requireProperties(
                        // todo: evaluate whether the Connect properties are required here
                        CoverityPlugin.COVERITY_PROJECT);

    }

    @Override
    public void execute(SensorContext context) {
        Settings settings = context.settings();

        boolean enabled = settings.getBoolean(CoverityPlugin.COVERITY_ENABLE);

        int totalDefectsCounter = 0;
        int highImpactDefectsCounter = 0;
        int mediumImpactDefectsCounter = 0;
        int lowImpactDefectsCounter = 0;

        LOG.info(CoverityPlugin.COVERITY_ENABLE + "=" + enabled);

        if(!enabled) {
            return;
        }

        //make sure to use the right SAAJ library. The one included with some JREs is missing a required file (a
        // LocalStrings bundle)
        ClassLoader oldCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        System.setProperty("javax.xml.soap.MetaFactory", "com.sun.xml.messaging.saaj.soap.SAAJMetaFactoryImpl");

        String host = settings.getString(CoverityPlugin.COVERITY_CONNECT_HOSTNAME);
        int port = settings.getInt(CoverityPlugin.COVERITY_CONNECT_PORT);
        String user = settings.getString(CoverityPlugin.COVERITY_CONNECT_USERNAME);
        String password = settings.getString(CoverityPlugin.COVERITY_CONNECT_PASSWORD);
        boolean ssl = settings.getBoolean(CoverityPlugin.COVERITY_CONNECT_SSL);

        String covProject = settings.getString(CoverityPlugin.COVERITY_PROJECT);
        String stripPrefix = settings.getString(CoverityPlugin.COVERITY_PREFIX);
        String covSrcDir = settings.getString(CoverityPlugin.COVERITY_SOURCE_DIRECTORY);

        /**
         * Checks whether a project has been specified.
         */
        if(covProject == null || covProject.isEmpty()) {
            LOG.error("Couldn't find project: " + covProject);
            Thread.currentThread().setContextClassLoader(oldCL);
            return;
        }

        CIMClient instance = new CIMClient(host, port, user, password, ssl);

        //find the configured project
        ProjectDataObj covProjectObj = null;
        try {
            covProjectObj = instance.getProject(covProject);
            if(covProjectObj == null) {
                LOG.error("Couldn't find project: " + covProject);
                Thread.currentThread().setContextClassLoader(oldCL);
                return;
            }            
            LOG.info("Found project: " + covProject + " (" + covProjectObj.getProjectKey() + ")");

        } catch(Exception e) {
            LOG.error("Error while trying to find project: " + covProject);
            Thread.currentThread().setContextClassLoader(oldCL);
            return;
        }

        try {
            LOG.info("Fetching defects for project: " + covProject);

            List<MergedDefectDataObj> defects = instance.getDefects(covProject);

            Map<Long, StreamDefectDataObj> streamDefects = instance.getStreamDefectsForMergedDefects(defects);

            LOG.info("Found " + streamDefects.size() + " defects");

            String currentDir = System.getProperty("user.dir");
            File currenDirFile = new File(currentDir);
            LOG.info("Current Directory: " + currentDir);

            List<File> listOfFiles = new ArrayList<File>();
            String sonarSourcesString = null;
            if(covSrcDir != null && !covSrcDir.isEmpty()){
                sonarSourcesString = covSrcDir;
            } else {
                sonarSourcesString = settings.getString("sonar.sources");
            }
            if(sonarSourcesString != null && !sonarSourcesString.isEmpty()){
                List<String> sonarSources = Arrays.asList(sonarSourcesString.split(","));
                for(String dir : sonarSources){
                    File folder = new File(dir);
                    listOfFiles.addAll(CoverityUtil.listFiles(folder));
                }
            }

            for(MergedDefectDataObj mddo : defects) {

                String status = "";
                String impact = "";

                List<DefectInstanceDataObj> didos = streamDefects.get(mddo.getCid()).getDefectInstances();

                if (didos == null || didos.isEmpty()) {
                    LOG.info("The merged defect with CID " + mddo.getCid() + "has no defect instances defined.");
                    continue;
                }

                impact = didos.get(0).getImpact().getDisplayName();

                List<DefectStateAttributeValueDataObj> listOfAttributes = mddo.getDefectStateAttributeValues();

                for(DefectStateAttributeValueDataObj defectAttribute : listOfAttributes){
                    if(defectAttribute.getAttributeDefinitionId().getName().equals("DefectStatus")){
                        status = defectAttribute.getAttributeValueId().getName();
                    }
                }

                if ("Dismissed".equals(status) || "Fixed".equals(status)) {
                    LOG.info("Skipping resolved defect (CID " + mddo.getCid() + ", status '" + status + "')");
                    continue;
                }

                InputFile inputFile;
                String filePath = mddo.getFilePathname();
                if (stripPrefix != null && !stripPrefix.isEmpty() && filePath.startsWith(stripPrefix)){
                    String strippedFilePath = filePath.substring(stripPrefix.length());
                    filePath = new File(currenDirFile, strippedFilePath).getAbsolutePath();
                    LOG.info("Full path after prefix being stripped: " + filePath);
                }

                if (platform.startsWith("Windows")) {
                    filePath = filePath.replace("/", "\\");
                }

                final FileSystem fileSystem = context.fileSystem();
                inputFile = fileSystem.inputFile(fileSystem.predicates().hasPath(filePath));

                if(impact != null){
                    totalDefectsCounter++;
                    if (impact.equals(HIGH)) {
                        highImpactDefectsCounter++;
                    }else if (impact.equals(MEDIUM)) {
                        mediumImpactDefectsCounter++;
                    }else {
                        lowImpactDefectsCounter++;
                    }
                }

                if(inputFile == null) {
                    for(File possibleFile : listOfFiles){
                        if(possibleFile.getAbsolutePath().endsWith(filePath)){
                            inputFile = fileSystem.inputFile(fileSystem.predicates().hasPath(possibleFile.getAbsolutePath()));
                            break;
                        }
                    }
                }

                if(inputFile == null) {
                    LOG.info("Cannot find the file '" + filePath + "', skipping defect (CID " + mddo.getCid() + ")");
                    continue;
                }

                String lang = inputFile.language();
                // This is a way to introduce support for community c++
                if (lang == null) {
                    lang = context.settings().getString(CoreProperties.PROJECT_LANGUAGE_PROPERTY);
                }

                for(DefectInstanceDataObj dido : didos) {
                    //find the main event, so we can use its line number
                    EventDataObj mainEvent = getMainEvent(dido);


                    String key = dido.getDomain() + "_" + dido.getCheckerName();
                    org.sonar.api.rule.RuleKey rk = CoverityUtil.getRuleKey(lang, key);

                    ActiveRule ar = context.activeRules().find(rk);
                    if(ar == null){
                        rk = CoverityUtil.getRuleKey(lang, key + "_" + "generic");
                        ar = context.activeRules().find(rk);
                    }
                    if(ar == null){
                        rk = CoverityUtil.getRuleKey(lang, key + "_" + "none");
                        ar = context.activeRules().find(rk);
                    }

                    LOG.debug("mainEvent=" + mainEvent);
                    LOG.debug("ar=" + ar);
                    if(mainEvent != null && ar != null) {
                        LOG.debug("instance=" + instance);
                        LOG.debug("covProjectObj=" + covProjectObj);
                        LOG.debug("mddo=" + mddo);
                        LOG.debug("dido=" + dido);
                        String message = getIssueMessage(instance, covProjectObj, mddo, dido);

                        final DefaultTextPointer start = new DefaultTextPointer(mainEvent.getLineNumber(), 0);


                        NewIssue issue = context.newIssue();

                        NewIssueLocation issueLocation = issue
                                .newLocation()
                                .on(inputFile)
                                .at(new DefaultTextRange(start, start))
                                .message(message);

                        issue.forRule(ar.ruleKey())
                                .at(issueLocation);

                        LOG.debug("issue=" + issue);
                        issue.save();
                    } else {
                        LOG.info("Couldn't create issue: " + mddo.getCid());
                    }
                }
            }
        } catch(Exception e) {
            LOG.error("Error fetching defects", e);
        }

        totalDefects = totalDefectsCounter;
        highImpactDefects = highImpactDefectsCounter;
        mediumImpactDefects = mediumImpactDefectsCounter;
        lowImpactDefects = lowImpactDefectsCounter;

        Thread.currentThread().setContextClassLoader(oldCL);
        // Display a clickable Coverity Logo
        getCoverityLogoMeasures(context, instance, covProjectObj);
    }

    protected String getIssueMessage(CIMClient instance, ProjectDataObj covProjectObj, MergedDefectDataObj mddo, DefectInstanceDataObj dido) throws CovRemoteServiceException_Exception, IOException {
        String url = getDefectURL(instance, covProjectObj, mddo);

        String description = dido.getLongDescription();

        return description + "\n\nView in Coverity Connect: \n" + url;
    }

    //Replacing "#" for "&" in order to fix bug 62066.
    protected String getDefectURL(CIMClient instance, ProjectDataObj covProjectObj, MergedDefectDataObj mddo) {
        return String.format("%s://%s:%d/sourcebrowser.htm?projectId=%s&mergedDefectId=%d",
                instance.isUseSSL() ? "https" : "http", instance.getHost(), instance.getPort(), covProjectObj.getProjectKey(), mddo.getCid());
    }

    protected EventDataObj getMainEvent(DefectInstanceDataObj dido) {
        if(dido.getEvents() != null && !dido.getEvents().isEmpty()){
            for(EventDataObj edo : dido.getEvents()) {
                if(edo.isMain()) {
                    return edo;
                }
            }
            // If no event is marked as "main" the first event is returned.
            return dido.getEvents().get(0);
        }
        return null;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    /*
    * This method constructs measures from metrics. It adds the required data to the measures, such as a URL, and then
    * saves the measures into sensorContext. This method is called by analyse().
    * */
    private void getCoverityLogoMeasures(SensorContext sensorContext, CIMClient client, ProjectDataObj covProjectObj) {
        String covProject = sensorContext.settings().getString(CoverityPlugin.COVERITY_PROJECT);
        if (covProject != null) {
            sensorContext
                    .newMeasure()
                    .forMetric(CoverityPluginMetrics.COVERITY_PROJECT_NAME)
                    .on(sensorContext.module())
                    .withValue(covProject)
                    .save();
        }


        String ProjectUrl = createURL(client);
        if (ProjectUrl != null) {
            sensorContext
                    .newMeasure()
                    .forMetric(CoverityPluginMetrics.COVERITY_URL_CIM_METRIC)
                    .on(sensorContext.module())
                    .withValue(ProjectUrl)
                    .save();
        }

        String ProductKey= String.valueOf(covProjectObj.getProjectKey());
        ProjectUrl = ProjectUrl+"reports.htm#p"+ProductKey;
        if (ProjectUrl != null) {
            sensorContext
                .newMeasure()
                .forMetric(CoverityPluginMetrics.COVERITY_PROJECT_URL)
                .on(sensorContext.module())
                .withValue(ProjectUrl)
                .save();
        }

        sensorContext
                .newMeasure()
                .forMetric(CoverityPluginMetrics.COVERITY_OUTSTANDING_ISSUES)
                .on(sensorContext.module())
                .withValue(totalDefects)
                .save();

        sensorContext
                .newMeasure()
                .forMetric(CoverityPluginMetrics.COVERITY_HIGH_IMPACT)
                .on(sensorContext.module())
                .withValue(highImpactDefects)
                .save();

        sensorContext
                .newMeasure()
                .forMetric(CoverityPluginMetrics.COVERITY_MEDIUM_IMPACT)
                .on(sensorContext.module())
                .withValue(mediumImpactDefects)
                .save();

        sensorContext
                .newMeasure()
                .forMetric(CoverityPluginMetrics.COVERITY_LOW_IMPACT)
                .on(sensorContext.module())
                .withValue(lowImpactDefects)
                .save();
    }
}
