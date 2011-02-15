/*******************************************************************************
 * Copyright (C) 2010 The University of Manchester   
 * 
 *  Modifications to the initial code base are copyright of their
 *  respective authors, or their employers as appropriate.
 * 
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2.1 of
 *  the License, or (at your option) any later version.
 *    
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *    
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 ******************************************************************************/
package uk.org.taverna.platform.report;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.sf.taverna.t2.reference.T2Reference;
import uk.org.taverna.scufl2.api.activity.Activity;
import uk.org.taverna.scufl2.api.core.Processor;
import uk.org.taverna.scufl2.api.core.Workflow;

/**
 * 
 * 
 * @author David Withers
 */
public abstract class WorkflowReport extends StatusReport {

	private final Workflow workflow;

	private final Map<String, T2Reference> inputs = new HashMap<String, T2Reference>();

	private final Map<String, T2Reference> outputs = new HashMap<String, T2Reference>();

	private final Set<ProcessorReport> processorReports = new HashSet<ProcessorReport>();

	public WorkflowReport(Workflow workflow) {
		this.workflow = workflow;
	}

	public WorkflowReport(Workflow workflow, Map<String, T2Reference> inputs) {
		this.workflow = workflow;
		if (inputs != null) {
			this.inputs.putAll(inputs);
		}
	}

	/**
	 * @return the workflow
	 */
	public Workflow getWorkflow() {
		return workflow;
	}

	/**
	 * Returns the inputs for the workflow run.
	 * 
	 * If there are no inputs an empty map is returned.
	 * 
	 * @return the inputs
	 */
	public Map<String, T2Reference> getInputs() {
		return inputs;
	}

	/**
	 * Returns the outputs from the workflow run.
	 * 
	 * If there are no outputs an empty map is returned.
	 * 
	 * @return the outputs
	 */
	public Map<String, T2Reference> getOutputs() {
		return outputs;
	}

	public void addProcessorReport(ProcessorReport processorReport) {
		processorReports.add(processorReport);
	}

	/**
	 * @return the processorReports
	 */
	public Set<ProcessorReport> getProcessorReports() {
		return processorReports;
	}

	public Set<ProcessorReport> getAllProcessorReports() {
		Set<ProcessorReport> allProcessorReports = new HashSet<ProcessorReport>();
		for (ProcessorReport processorReport : getProcessorReports()) {
			allProcessorReports.add(processorReport);
			for (ActivityReport activityReport : processorReport.getActivityReports()) {
				WorkflowReport nestedWorkflowReport = activityReport.getNestedWorkflowReport();
				if (nestedWorkflowReport != null) {
					allProcessorReports.addAll(nestedWorkflowReport.getAllProcessorReports());
				}
			}
		}
		return allProcessorReports;
	}

	public abstract ProcessorReport createProcessorReport(Processor processor,
			WorkflowReport parentReport);

	public abstract ActivityReport createActivityReport(Activity activity,
			ProcessorReport parentReport);

	public String toString() {
		StringBuilder sb = new StringBuilder();
		int max = getLongestName();
		sb.append(spaces(max + 1));
		sb.append("Status    ");
		sb.append("Queued    ");
		sb.append("Started   ");
		sb.append("Complete  ");
		sb.append("Errors    ");
		sb.append("Started             ");
		sb.append("Finished\n");
		sb.append(workflow.getName());
		sb.append(spaces(max - workflow.getName().length() + 1));
		sb.append(getState());
		sb.append(spaces(10 - getState().name().length()));
		sb.append("-");
		sb.append(spaces(9));
		sb.append("-");
		sb.append(spaces(9));
		sb.append("-");
		sb.append(spaces(9));
		sb.append("-");
		sb.append(spaces(9));
		sb.append(dates(getStartedDate(), getCompletedDate()));
		for (ProcessorReport processorReport : processorReports) {
			String processorName = processorReport.getProcessor().getName();
			sb.append(processorName);
			sb.append(spaces(max - processorName.length() + 1));

			State processorState = processorReport.getState();
			sb.append(processorState);
			sb.append(spaces(10 - processorState.name().length()));

			String jobsQueued = String.valueOf(processorReport.getJobsQueued());
			sb.append(jobsQueued);
			sb.append(spaces(10 - jobsQueued.length()));

			String jobsStarted = String.valueOf(processorReport.getJobsStarted());
			sb.append(jobsStarted);
			sb.append(spaces(10 - jobsStarted.length()));

			String jobsCompleted = String.valueOf(processorReport.getJobsCompleted());
			sb.append(jobsCompleted);
			sb.append(spaces(10 - jobsCompleted.length()));

			String jobsCompletedWithErrors = String.valueOf(processorReport
					.getJobsCompletedWithErrors());
			sb.append(jobsCompletedWithErrors);
			sb.append(spaces(10 - jobsCompletedWithErrors.length()));

			sb.append(dates(processorReport.getStartedDate(), processorReport.getCompletedDate()));
		}
		return sb.toString();
	}

	private String dates(Date started, Date stopped) {
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		StringBuilder sb = new StringBuilder();
		if (started != null) {
			sb.append(dateFormat.format(started));
			sb.append(spaces(1));
		} else {
			sb.append("-");
			sb.append(spaces(19));
		}
		if (stopped != null) {
			sb.append(dateFormat.format(stopped) + "\n");
		} else {
			sb.append("-\n");
		}
		return sb.toString();
	}

	private int getLongestName() {
		int result = 0;
		result = Math.max(result, workflow.getName().length());
		for (ProcessorReport processorReport : getAllProcessorReports()) {
			result = Math.max(result, processorReport.getProcessor().getName().length());
		}
		return result;
	}

	private String spaces(int length) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < length; i++) {
			sb.append(' ');
		}
		return sb.toString();
	}

}
