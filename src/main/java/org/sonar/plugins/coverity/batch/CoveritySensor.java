/*
 * Coverity Sonar Plugin
 * Copyright (C) 2014 Coverity, Inc.
 * support@coverity.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package org.sonar.plugins.coverity.batch;

import com.coverity.ws.v6.*;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.Issue;
import org.sonar.api.measures.Measure;
import org.sonar.api.measures.Metric;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.ProjectFileSystem;
import org.sonar.api.resources.Resource;
import org.sonar.api.rules.ActiveRule;
import org.sonar.api.rules.Rule;
import org.sonar.plugins.coverity.CoverityPlugin;
import org.sonar.plugins.coverity.base.CoverityPluginMetrics;
import org.sonar.plugins.coverity.util.CoverityUtil;
import org.sonar.plugins.coverity.ws.CIMClient;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sonar.plugins.coverity.util.*;
import org.sonar.plugins.coverity.ws.TripleFromDefects;

import static org.sonar.plugins.coverity.base.CoverityPluginMetrics.*;
import static org.sonar.plugins.coverity.util.CoverityUtil.createURL;

public class CoveritySensor implements Sensor {
    private static final Logger LOG = LoggerFactory.getLogger(CoveritySensor.class);
    private final ResourcePerspectives resourcePerspectives;
    private Settings settings;
    private RulesProfile profile;

    String HIGH = new String("High");
    String MEDIUM = new String("Medium");
    String LOW = new String("Low");

    private Map<TripleFromDefects, CheckerPropertyDataObj> mapOfCheckerPropertyDataObj =null;
    private int totalDefects = 0;
    private int highImpactDefects = 0;
    private int mediumImpactDefects = 0;
    private int lowImpactDefects = 0;
    private String TOTALDEFECTS = null;
    private String HIGHIMPACTDEFECTS = null;
    private String MEDIUMIMPACTDEFECTS = null;
    private String LOWIMPACTDEFECTS = null;

    public CoveritySensor(Settings settings, RulesProfile profile, ResourcePerspectives resourcePerspectives) {
        this.settings = settings;
        this.profile = profile;
        this.resourcePerspectives = resourcePerspectives;
    }

    public boolean shouldExecuteOnProject(Project project) {
        boolean enabled = settings.getBoolean(CoverityPlugin.COVERITY_ENABLE);
        int active = profile.getActiveRulesByRepository(CoverityPlugin.REPOSITORY_KEY + "-" + project.getLanguageKey()).size();
        return enabled && active > 0;
    }

    public void analyse(Project project, SensorContext sensorContext) {
        boolean enabled = settings.getBoolean(CoverityPlugin.COVERITY_ENABLE);

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

        CIMClient instance = new CIMClient(host, port, user, password, ssl);
        mapOfCheckerPropertyDataObj=instance.getMapOfCheckerPropertyDataObj();

        //find the configured project
        ProjectDataObj covProjectObj = null;
        try {
            covProjectObj = instance.getProject(covProject);
            LOG.info("Found project: " + covProject + " (" + covProjectObj.getProjectKey() + ")");

            if(covProjectObj == null) {
                LOG.error("Couldn't find project: " + covProject);
                Thread.currentThread().setContextClassLoader(oldCL);
                return;
            }
        } catch(Exception e) {
            LOG.error("Error while trying to find project: " + covProject);
            Thread.currentThread().setContextClassLoader(oldCL);
            return;
        }

        LOG.debug(profile.toString());
        for(ActiveRule ar : profile.getActiveRulesByRepository(CoverityPlugin.REPOSITORY_KEY + "-" + project.getLanguageKey())) {
            LOG.debug(ar.toString());
        }

        try {
            LOG.info("Fetching defects for project: " + covProject);
            List<MergedDefectDataObj> defects = instance.getDefects(covProject);

            Map<Long, StreamDefectDataObj> streamDefects = instance.getStreamDefectsForMergedDefects(defects);

            LOG.info("Found " + streamDefects.size() + " defects");

            for(MergedDefectDataObj mddo : defects) {
                String filePath = mddo.getFilePathname();
                Resource res = getResourceForFile(filePath, project.getFileSystem());

                TripleFromDefects tripleFromMddo = new TripleFromDefects(mddo.getCheckerName(),
                        mddo.getCheckerSubcategory(), mddo.getDomain());

                CheckerPropertyDataObj checkerPropertyDataObj=mapOfCheckerPropertyDataObj.get(tripleFromMddo);
                String impact = checkerPropertyDataObj.getImpact();

                //if(checkerPropertyDataObj!=null){
                    totalDefects++; 
                    if (impact.equals(HIGH)) {
                        highImpactDefects++;
                    }
                    if (impact.equals(MEDIUM)) {
                        mediumImpactDefects++;
                    }
                    if (impact.equals(LOW)) {
                        lowImpactDefects++;
                    }
                //}

                if(res == null) {
                    LOG.info("Skipping defect (CID " + mddo.getCid() + ") because the source file could not be found.");
                    continue;
                }

                for(DefectInstanceDataObj dido : streamDefects.get(mddo.getCid()).getDefectInstances()) {
                    //find the main event, so we can use its line number
                    EventDataObj mainEvent = getMainEvent(dido);

                    Issuable issuable = resourcePerspectives.as(Issuable.class, res);

                    ActiveRule ar = profile.getActiveRule(CoverityUtil.getRuleKey(dido).repository(), CoverityUtil.getRuleKey(dido).rule());

                    LOG.debug("mainEvent=" + mainEvent);
                    LOG.debug("issuable=" + issuable);
                    LOG.debug("ar=" + ar);
                    if(mainEvent != null && issuable != null && ar != null) {
                        LOG.debug("instance=" + instance);
                        LOG.debug("ar.getRule()=" + ar.getRule());
                        LOG.debug("covProjectObj=" + covProjectObj);
                        LOG.debug("mddo=" + mddo);
                        LOG.debug("dido=" + dido);
                        LOG.debug("ar.getRule().getDescription()=" + ar.getRule().getDescription());
                        String message = getIssueMessage(instance, ar.getRule(), covProjectObj, mddo, dido);

                        Issue issue = issuable.newIssueBuilder()
                                .ruleKey(ar.getRule().ruleKey())
                                .line(mainEvent.getLineNumber())
                                .message(message)
                                .build();
                        LOG.debug("issue=" + issue);
                        boolean result = issuable.addIssue(issue);
                        LOG.debug("result=" + result);
                    } else {
                        LOG.info("Couldn't create issue: " + mddo.getCid());
                    }
                }
            }
        } catch(Exception e) {
            LOG.error("Error fetching defects");
            e.printStackTrace();
        }

        TOTALDEFECTS= String.valueOf(totalDefects);
        HIGHIMPACTDEFECTS= String.valueOf(highImpactDefects);
        MEDIUMIMPACTDEFECTS= String.valueOf(mediumImpactDefects);
        LOWIMPACTDEFECTS= String.valueOf(lowImpactDefects);

        Thread.currentThread().setContextClassLoader(oldCL);
        //Display a clickable Coverity Logo
        getCoverityLogoMeasures(sensorContext, instance, covProjectObj);
    }

    protected String getIssueMessage(CIMClient instance, Rule rule, ProjectDataObj covProjectObj, MergedDefectDataObj mddo, DefectInstanceDataObj dido) throws CovRemoteServiceException_Exception, IOException {
        String url = getDefectURL(instance, covProjectObj, mddo);

        LOG.debug("rule:" + rule);
        LOG.debug("description:" + rule.getDescription());

        return rule.getDescription() + "\n\nView in Coverity Connect: \n" + url;
    }

    protected String getDefectURL(CIMClient instance, ProjectDataObj covProjectObj, MergedDefectDataObj mddo) {
        return String.format("http://%s:%d/sourcebrowser.htm?projectId=%s#mergedDefectId=%d",
                instance.getHost(), instance.getPort(), covProjectObj.getProjectKey(), mddo.getCid());
    }

    protected EventDataObj getMainEvent(DefectInstanceDataObj dido) {
        for(EventDataObj edo : dido.getEvents()) {
            if(edo.isMain()) {
                return edo;
            }
        }
        return null;
    }

    protected Resource getResourceForFile(String filePath, ProjectFileSystem fileSystem) {
        File f = new File(filePath);
        Resource ret;
        ret = org.sonar.api.resources.JavaFile.fromIOFile(f, fileSystem.getSourceDirs(), false);
        if(ret == null) {
            ret = org.sonar.api.resources.File.fromIOFile(f, fileSystem.getSourceDirs());
        } else {
            //LOG.info("java file! : " + ret);
        }

        return ret;
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
        {
            Measure measure = new Measure(COVERITY_URL_CIM_METRIC);
            String CIM_URL = createURL(client);
            measure.setData(CIM_URL);
            sensorContext.saveMeasure(measure);
        }

        {
            Measure measure = new Measure(COVERITY_PROJECT_NAME);
            String covProject = settings.getString(CoverityPlugin.COVERITY_PROJECT);
            measure.setData(covProject);
            sensorContext.saveMeasure(measure);
        }

        {
            Measure measure = new Measure(COVERITY_PROJECT_URL);
            String ProjectUrl = createURL(client);
            String ProductKey = null;
            ProductKey= String.valueOf(covProjectObj.getProjectKey());
            ProjectUrl = ProjectUrl+"reports.htm#p"+ProductKey;
            measure.setData(ProjectUrl);
            sensorContext.saveMeasure(measure);
        }

        {
            Measure measure = new Measure(COVERITY_OUTSTANDING_ISSUES);
            measure.setData(TOTALDEFECTS);
            sensorContext.saveMeasure(measure);
        }

        {
            Measure measure = new Measure(COVERITY_HIGH_IMPACT);
            measure.setData(HIGHIMPACTDEFECTS);
            sensorContext.saveMeasure(measure);
        }

        {
            Measure measure = new Measure(COVERITY_MEDIUM_IMPACT);
            measure.setData(MEDIUMIMPACTDEFECTS);
            sensorContext.saveMeasure(measure);
        }

        {
            Measure measure = new Measure(COVERITY_LOW_IMPACT);
            measure.setData(LOWIMPACTDEFECTS);
            sensorContext.saveMeasure(measure);
        }
    }
}
