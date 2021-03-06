/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements. See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.taverna.provenance.item;

import java.sql.Timestamp;

import org.apache.taverna.facade.WorkflowInstanceFacade.State;
import org.apache.taverna.provenance.vocabulary.SharedVocabulary;

/**
 * Informs the {@link ProvenanceConnector} that a workflow has been run to
 * completion. If a {@link ProvenanceConnector} receives this event then it
 * means that there are no further events to come and that the workflow has been
 * enacted to completion
 * 
 * @author Ian Dunlop
 */
public class DataflowRunComplete extends AbstractProvenanceItem {
	private SharedVocabulary eventType = SharedVocabulary.END_WORKFLOW_EVENT_TYPE;
	private Timestamp invocationEnded;
	private State state;

	public Timestamp getInvocationEnded() {
		return invocationEnded;
	}

	@Override
	public SharedVocabulary getEventType() {
		return eventType;
	}

	public void setInvocationEnded(Timestamp invocationEnded) {
		this.invocationEnded = invocationEnded;
	}

	public void setState(State state) {
		this.state = state;
	}

	public State getState() {
		return state;
	}
}
