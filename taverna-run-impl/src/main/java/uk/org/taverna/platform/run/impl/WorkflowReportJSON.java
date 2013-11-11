package uk.org.taverna.platform.run.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.util.Date;

import org.purl.wf4ever.robundle.Bundle;
import org.purl.wf4ever.robundle.manifest.Manifest.PathMixin;

import uk.org.taverna.databundle.DataBundles;
import uk.org.taverna.platform.report.State;
import uk.org.taverna.platform.report.StatusReport;
import uk.org.taverna.platform.report.WorkflowReport;
import uk.org.taverna.scufl2.api.common.Scufl2Tools;
import uk.org.taverna.scufl2.api.common.URITools;
import uk.org.taverna.scufl2.api.common.WorkflowBean;
import uk.org.taverna.scufl2.api.container.WorkflowBundle;
import uk.org.taverna.scufl2.api.core.Workflow;
import uk.org.taverna.scufl2.api.io.ReaderException;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.StdDateFormat;

public class WorkflowReportJSON {
    
    public void save(WorkflowReport wfReport, Path path) throws IOException {
//        ObjectNode objNode = save(wfReport);
        
//        injectContext(objNode);
        
        ObjectMapper om = makeObjectMapperForSave();
//        Files.createFile(path);
        try (Writer w = Files.newBufferedWriter(path,
                Charset.forName("UTF-8"), StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            om.writeValue(w, wfReport);
        }
    }
    
    protected static ObjectMapper makeObjectMapperForLoad() {
        ObjectMapper om = new ObjectMapper();
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return om;
    }
    
    protected static ObjectMapper makeObjectMapperForSave() {
        ObjectMapper om = new ObjectMapper();
        om.enable(SerializationFeature.INDENT_OUTPUT);
        om.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        om.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        om.disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS);
        om.disable(SerializationFeature.WRITE_NULL_MAP_VALUES);
        om.disable(SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED);        
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(SerializationFeature.WRITE_NULL_MAP_VALUES);
        om.addMixInAnnotations(Path.class, PathMixin.class);
        om.setSerializationInclusion(Include.NON_NULL);
        return om;
    }

    private void injectContext(ObjectNode objNode) {
        objNode.with("@context").put("wfprov", "http://purl.org/wf4ever/wfprov#");
        objNode.with("@context").put("wfdesc", "http://purl.org/wf4ever/wfdesc#");
        objNode.with("@context").put("prov", "http://www.w3.org/ns/prov#");
    }

    public void save(WorkflowReport wfReport) throws IOException {
        Path path = DataBundles.getWorkflowRunReport(wfReport.getDataBundle());
        save(wfReport, path);
        DataBundles.setWorkflowBundle(wfReport.getDataBundle(), wfReport.getSubject().getParent());
    }

    public WorkflowReport load(Bundle bundle) throws IOException, ReaderException, ParseException {
        Path path = DataBundles.getWorkflowRunReport(bundle);
        WorkflowBundle workflow = DataBundles.getWorkflowBundle(bundle);
        return load(path, workflow);
    }

    private static Scufl2Tools scufl2Tools = new Scufl2Tools();
    private static URITools uriTools = new URITools();
    
    public WorkflowReport load(Path workflowReportJson, WorkflowBundle workflowBundle) throws IOException, ParseException {
        JsonNode json = loadWorkflowReportJson(workflowReportJson);
        if (! json.isObject()) {
            throw new IOException("Invalid workflow report, expected JSON Object:\n" + json);
        }
        return parseWorkflowReport(json, workflowReportJson, workflowBundle);
    }

    protected WorkflowReport parseWorkflowReport(JsonNode reportJson, Path workflowReportJson,
            WorkflowBundle workflowBundle) throws ParseException {
        Workflow wf = (Workflow) getSubject(reportJson, workflowBundle);        
        WorkflowReport workflowReport = new WorkflowReport(wf);
        parseDates(reportJson, workflowReport);
        
        return workflowReport;
        
    }
    
    StdDateFormat STD_DATE_FORMAT = new StdDateFormat();

    protected void parseDates(JsonNode json, 
            @SuppressWarnings("rawtypes") StatusReport report) throws ParseException {

       Date createdDate = getDate(json, "createdDate");
       if (createdDate != null) {
           report.setCreatedDate(createdDate);
       }
       
       Date startedDate = getDate(json, "startedDate");
       if (startedDate != null) {
           report.setStartedDate(startedDate);
       }

       // Special case for paused and resumed dates>
       for (JsonNode s : json.path("pausedDates")) {
           Date pausedDate = STD_DATE_FORMAT.parse(s.asText());
           report.setPausedDate(pausedDate);
       }
       Date pausedDate = getDate(json, "pausedDate");
       if (report.getPausedDates().isEmpty() && pausedDate != null) {
           // "pausedDate" is normally redundant (last value of "pausedDates")
           // but here for some reason the list is missing, so we'll
           // parse it separately.
           // Note that if there was a list,  we will ignore "pauseDate" no matter its value
           report.setPausedDate(pausedDate); 
       }
       
       for (JsonNode s : json.path("resumedDates")) {
           Date resumedDate = STD_DATE_FORMAT.parse(s.asText());
           report.setPausedDate(resumedDate);
       }
       Date resumedDate = getDate(json, "resumedDate");
       if (report.getResumedDates().isEmpty() && resumedDate != null) {
           // Same fall-back as for "pausedDate" above
           report.setResumedDate(resumedDate); 
       }
       
       
       Date cancelledDate = getDate(json, "cancelledDate");
       if (cancelledDate != null) {
           report.setCancelledDate(cancelledDate);
       }
       
       Date failedDate = getDate(json, "failedDate");
       if (failedDate != null) {
           report.setFailedDate(failedDate);
       }
       
       Date completedDate = getDate(json, "completedDate");
       if (completedDate != null) {
           report.setCompletedDate(completedDate);
       }
       
       try {
           State state = State.valueOf(json.get("state").asText());
           report.setState(state);
       } catch (IllegalArgumentException ex) {
           throw new ParseException("Invalid state: " + json.get("state"), -1);
       }
    }

    protected Date getDate(JsonNode json, String name) throws ParseException {
        String date = json.path(name).asText();
        if (date.isEmpty()) {
            return null;
        }
        return STD_DATE_FORMAT.parse(date);
    }

    private WorkflowBean getSubject(JsonNode reportJson, WorkflowBundle workflowBundle) {
        URI subjectUri = URI.create(reportJson.path("subject").asText());
        return uriTools.resolveUri(subjectUri, workflowBundle);
    }

    protected JsonNode loadWorkflowReportJson(Path path) throws IOException, JsonProcessingException {
        ObjectMapper om = makeObjectMapperForLoad();
        try (InputStream stream = Files.newInputStream(path)) {    
            return om.readTree(stream);
        }
    }

}
