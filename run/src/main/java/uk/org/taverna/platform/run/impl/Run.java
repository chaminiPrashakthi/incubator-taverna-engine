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
package uk.org.taverna.platform.run.impl;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

import net.sf.taverna.t2.reference.ReferenceService;
import net.sf.taverna.t2.reference.T2Reference;

import org.apache.log4j.Logger;

import uk.org.taverna.platform.execution.api.ExecutionService;
import uk.org.taverna.platform.execution.api.InvalidExecutionIdException;
import uk.org.taverna.platform.execution.api.InvalidWorkflowException;
import uk.org.taverna.platform.report.State;
import uk.org.taverna.platform.report.WorkflowReport;
import uk.org.taverna.platform.run.api.RunProfile;
import uk.org.taverna.platform.run.api.RunProfileException;
import uk.org.taverna.platform.run.api.RunStateException;
import uk.org.taverna.scufl2.api.container.WorkflowBundle;
import uk.org.taverna.scufl2.api.core.Workflow;
import uk.org.taverna.scufl2.api.profiles.Profile;

/**
 * A single run of a {@link Workflow}.
 *
 * @author David Withers
 */
public class Run {

	private static final Logger logger = Logger.getLogger(Run.class);

	private final String ID, executionID;

	private Map<String, T2Reference> inputs;

	private final ExecutionService executionService;

	private final WorkflowReport workflowReport;

	private final WorkflowBundle workflowBundle;

	private final Workflow workflow;

	private final Profile profile;

	private final ReferenceService referenceService;

	/**
	 * Constructs a <code>Run</code> from the specified <code>RunProfile</code>.
	 *
	 * @param runProfile
	 *            the profile to create a <code>Run</code> from
	 * @throws InvalidWorkflowException
	 *             if the <code>Workflow</code> specified by the
	 *             <code>RunProfile</code> is not valid
	 * @throws RunProfileException
	 *             if the <code>RunProfile</code> does not contain the correct
	 *             information to run a <code>Workflow</code>
	 */
	public Run(RunProfile runProfile) throws InvalidWorkflowException,
			RunProfileException {
		if (runProfile.getWorkflowBundle() == null) {
			String message = "No WorkflowBundle specified in the RunProfile";
			logger.warn(message);
			throw new RunProfileException(message);
		} else {
			workflowBundle = runProfile.getWorkflowBundle();
		}
		if (runProfile.getWorkflow() == null) {
			if (workflowBundle.getMainWorkflow() == null) {
				String message = "No Workflow specified in either the RunProfile or the WorkflowBundle";
				logger.warn(message);
				throw new RunProfileException(message);
			} else {
				logger.info("No Workflow specified - using the main Workflow from the WorkflowBundle");
				workflow = workflowBundle.getMainWorkflow();
			}
		} else {
			workflow = runProfile.getWorkflow();
		}
		if (runProfile.getProfile() == null) {
			if (workflowBundle.getMainProfile() == null) {
				String message = "No Profile specified in either the RunProfile or the WorkflowBundle";
				logger.warn(message);
				throw new RunProfileException(message);
			} else {
				logger.info("No Profile specified - using the main Profile from the WorkflowBundle");
				profile = workflowBundle.getMainProfile();
			}
		} else {
			profile = runProfile.getProfile();
		}
		if (runProfile.getInputs() == null) {
			String message = "No workflow inputs in the RunProfile";
			logger.info(message);
		} else {
			inputs = runProfile.getInputs();
		}
		if (runProfile.getReferenceService() == null) {
			String message = "No ReferenceService specified in the RunProfile";
			logger.warn(message);
			throw new RunProfileException(message);
		} else {
			referenceService = runProfile.getReferenceService();
		}
		if (runProfile.getExecutionService() == null) {
			String message = "No ExecutionService specified in the RunProfile";
			logger.warn(message);
			throw new RunProfileException(message);
		} else {
			executionService = runProfile.getExecutionService();
		}
		ID = UUID.randomUUID().toString();
		executionID = executionService.createExecution(workflowBundle,
				workflow, profile, inputs, referenceService);
		try {
			workflowReport = executionService.getWorkflowReport(executionID);
		} catch (InvalidExecutionIdException e) {
			String message = "Error while creating a execution on the "
					+ executionService.getName();
			logger.error(message);
			throw new RuntimeException(message, e);
		}
	}

	/**
	 * Returns the identifier for this <code>Run</code>.
	 *
	 * @return the identifier for this <code>Run</code>
	 */
	public String getID() {
		return ID;
	}

	/**
	 * Returns the current {@link State} of the <code>Run</code>.
	 *
	 * A <code>Run</code>'s state can be CREATED, RUNNING, COMPLETED, PAUSED,
	 * CANCELLED or FAILED.
	 *
	 * @return the current <code>State</code> of the <code>Run</code>
	 */
	public State getState() {
		return workflowReport.getState();
	}

	/**
	 * Returns the inputs of the run.
	 *
	 * May be <code>null</code> if the <code>Workflow</code> doesn't require any
	 * inputs.
	 *
	 * @return the inputs for the <code>Workflow</code>
	 */
	public Map<String, T2Reference> getInputs() {
		return inputs;
	}

	/**
	 * Returns the outputs for the <code>Workflow</code>.
	 *
	 * May be <code>null</code> if the <code>Workflow</code> hasn't produced any
	 * outputs (yet).
	 *
	 * @return the outputs for the <code>Workflow</code>
	 */
	public Map<String, T2Reference> getOutputs() {
		return workflowReport.getOutputs();
	}

	public WorkflowReport getWorkflowReport() {
		return workflowReport;
	}

	/**
	 * Deletes a run.
	 *
	 * @throws InvalidExecutionIdException
	 */
	public void delete() throws InvalidExecutionIdException {
		synchronized (workflowReport) {
			executionService.delete(executionID);
		}
	}

	public void start() throws RunStateException, InvalidExecutionIdException {
		synchronized (workflowReport) {
			State state = workflowReport.getState();
			if (state.equals(State.CREATED)) {
				workflowReport.setStartedDate(new Date());
				executionService.start(executionID);
			} else {
				throw new RunStateException("Cannot start a " + state + " run.");
			}

		}
	}

	public void pause() throws RunStateException, InvalidExecutionIdException {
		synchronized (workflowReport) {
			State state = workflowReport.getState();
			if (state.equals(State.RUNNING)) {
				executionService.pause(executionID);
				workflowReport.setPausedDate(new Date());
			} else {
				throw new RunStateException("Cannot pause a " + state + " run.");
			}

		}
	}

	public void resume() throws RunStateException, InvalidExecutionIdException {
		synchronized (workflowReport) {
			State state = workflowReport.getState();
			if (state.equals(State.PAUSED)) {
				executionService.resume(executionID);
				workflowReport.setResumedDate(new Date());
			} else {
				throw new RunStateException("Cannot resume a " + state
						+ " run.");
			}
		}
	}

	public void cancel() throws RunStateException, InvalidExecutionIdException {
		synchronized (workflowReport) {
			State state = workflowReport.getState();
			if (state.equals(State.CANCELLED) || state.equals(State.COMPLETED)
					|| state.equals(State.FAILED)) {
				throw new RunStateException("Cannot cancel a " + state
						+ " run.");
			} else {
				executionService.cancel(executionID);
				workflowReport.setCancelledDate(new Date());
			}
		}
	}

}
