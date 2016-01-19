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

package org.apache.taverna.visit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author alanrw
 */
public class VisitReport {
	private static final String INDENTION = "    ";

	/**
	 * Enumeration of the possible status's in increasing severity: OK,
	 * WARNING,SEVERE
	 */
	public enum Status {
		OK, WARNING, SEVERE
	};

	/**
	 * A short message describing the state of the report
	 */
	private String message;
	/**
	 * An integer indicating the outcome of a visit relative to the VisitKind
	 */
	private int resultId;
	/**
	 * 
	 */
	private Status status;
	/**
	 * The object about which the report is made
	 */
	private Object subject;
	/**
	 * The sub-reports of the VisitReport
	 */
	private Collection<VisitReport> subReports = new ArrayList<>();
	/**
	 * The kind of visit that was made e.g. to check the health of a service or
	 * examine its up-stream error fragility
	 */
	private VisitKind kind;
	/**
	 * An indication of whether the visit report was generated by a time
	 * consuming visitor. This is used to check whether the VisitReport can be
	 * automatically junked.
	 */
	private boolean wasTimeConsuming;
	private Map<String, Object> propertyMap = new HashMap<>();
	private long checkTime;

	/**
	 * @return whether the VisitReport was generated by a time consuming visitor
	 */
	public boolean wasTimeConsuming() {
		return wasTimeConsuming;
	}

	/**
	 * @param wasTimeConsuming whether the VisitReport was generated by a time consuming visitot
	 */
	public void setWasTimeConsuming(boolean wasTimeConsuming) {
		this.wasTimeConsuming = wasTimeConsuming;
	}

	/**
	 * Constructs the Visit Report. The sub reports default to an empty list.
	 * 
	 * @param kind
	 *            - the type of visit performed
	 * @param subject
	 *            - the thing being tested.
	 * @param message
	 *            - a summary of the result of the test.
	 * @param resultId
	 *            - an identification of the type of result relative to the
	 *            VisitKind
	 * @param status
	 *            - the overall Status.
	 */
	public VisitReport(VisitKind kind, Object subject, String message,
			int resultId, Status status) {
		this(kind, subject, message, resultId, status,
				new ArrayList<VisitReport>());
	}
	
	/**
	 * Used internally by {@link #clone()}.
	 */
	protected VisitReport() {}

	/**
	 * Constructs the Visit Report
	 * 
	 * @param kind
	 *            - the type of visit performed
	 * @param subject
	 *            - the thing being tested.
	 * @param message
	 *            - a summary of the result of the test.
	 * @param resultId
	 *            - an identification of the type of result relative to the
	 *            VisitKind
	 * @param status - the overall Status.
	 * @param subReports
	 *            - a List of sub reports.
	 */
	public VisitReport(VisitKind kind, Object subject, String message,
			int resultId, Status status, Collection<VisitReport> subReports) {
		this.kind = kind;
		this.subject = subject;
		this.status = status;
		this.message = message;
		this.resultId = resultId;
		this.subReports = subReports;
		this.wasTimeConsuming = false;
		this.checkTime = 0;
	}

	/**
	 * @param kind The type of visit performed
	 * @param subject The thing that was visited
	 * @param message A summary of the result of the test
	 * @param resultId An indication of the type of the result relative to the kind of visit
	 * @param subReports A list of sub-reports
	 */
	public VisitReport(VisitKind kind, Object subject, String message,
			int resultId, Collection<VisitReport> subReports) {
		this(kind, subject, message, resultId, getWorstStatus(subReports),
				subReports);
	}

	/**
	 * @return An indication of the type of the result relative to the kind of visit
	 */
	public int getResultId() {
		return resultId;
	}

	/**
	 * @param resultId The type of the result of the visit relative to the kind of visit
	 */
	public void setResultId(int resultId) {
		this.resultId = resultId;
	}

	/**
	 * @return a message summarizing the report
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * Sets the message
	 * 
	 * @param message
	 *            a message summarizing the report
	 */
	public void setMessage(String message) {
		this.message = message;
	}

	/**
	 * Determines the overall Status. This is the most severe status of this
	 * report and all its sub reports.
	 * 
	 * @return the overall status
	 */
	public Status getStatus() {
		Status result = status;
		for (VisitReport report : subReports)
			if (report.getStatus().compareTo(result) > 0)
				result = report.getStatus();
		return result;
	}

	/**
	 * Sets the status of this report. Be aware that the overall status of this
	 * report may also be affected by its sub reports if they have a more severe
	 * Status.
	 * 
	 * @param status
	 * @see #getStatus
	 */
	public void setStatus(Status status) {
		this.status = status;
	}

	/**
	 * @return an Object representing the subject of this visit report
	 */
	public Object getSubject() {
		return subject;
	}

	/**
	 * @param subject
	 *            an Object representing the subject of this visit report
	 */
	public void setSubject(Object subject) {
		this.subject = subject;
	}

	/**
	 * Provides a list of sub reports. This list defaults an empty list, so it
	 * is safe to add new reports through this method.
	 * 
	 * @return a list of sub reports associated with this Visit Report
	 */
	public Collection<VisitReport> getSubReports() {
		return subReports;
	}

	/**
	 * Replaces the List of sub reports with those provided.
	 * 
	 * @param subReports
	 *            a list of sub reports
	 */
	public void setSubReports(Collection<VisitReport> subReports) {
		this.subReports = subReports;
	}

	/**
	 * 
	 * @return the kind of visit that was made.
	 */
	public VisitKind getKind() {
		return kind;
	}

	/**
	 * @param kind Specify the kind of visit that was made
	 */
	public void setKind(VisitKind kind) {
		this.kind = kind;
	}
	
	public void setProperty(String key, Object value) {
		propertyMap.put(key, value);
	}
	
	public Object getProperty(String key) {
		return propertyMap.get(key);
	}

	public Map<String, Object> getProperties() {
		return propertyMap;
	}
	
	/**
	 * Find the most recent ancestor (earliest in the list) of a given class from the list of ancestors
	 * 
	 * @param ancestors The list of ancestors to examine
	 * @param ancestorClass The class to search for
	 * @return The most recent ancestor, or null if no suitable ancestor
	 */
	public static Object findAncestor(List<Object> ancestors,
			Class<?> ancestorClass) {
		Object result = null;
		for (Object o : ancestors)
			if (ancestorClass.isInstance(o))
				return o;
		return result;
	}

	public void setCheckTime(long time) {
		this.checkTime = time;
	}

	public long getCheckTime() {
		return this.checkTime;
	}

	/**
	 * Determine the worst status from a collection of reports
	 * 
	 * @param reports
	 *            The collection of reports to examine
	 * @return The worst status
	 */
	public static Status getWorstStatus(Collection<VisitReport> reports) {
		Status currentStatus = Status.OK;
		for (VisitReport report : reports)
			if (currentStatus.compareTo(report.getStatus()) < 0)
				currentStatus = report.getStatus();
		return currentStatus;
	}

	@Override
	public String toString() {
		// TODO Use StringBuilder instead
		StringBuffer sb = new StringBuffer();
		visitReportToStringBuffer(sb, "");
		return sb.toString();
	}

	protected void visitReportToStringBuffer(
			StringBuffer sb, String indent) {	
		sb.append(indent);
		sb.append(getStatus());
		sb.append(' ');
		sb.append(getMessage());
		if (! propertyMap.isEmpty()) {
			sb.append(' ');
			sb.append(propertyMap);
		}
		sb.append('\n');
		indent = indent + INDENTION;
		for (VisitReport subReport : getSubReports())
			subReport.visitReportToStringBuffer(sb, indent);
	}
	
	@Override
	public VisitReport clone() throws CloneNotSupportedException {
		if (!getClass().equals(VisitReport.class))
			throw new CloneNotSupportedException("Can't clone subclass "
					+ getClass()
					+ ", reimplement clone() and use internalClone()");
		return internalClone(new VisitReport());
	}

	protected VisitReport internalClone(VisitReport newReport)
			throws CloneNotSupportedException {
		newReport.checkTime = this.checkTime;
		newReport.kind = this.kind;
		newReport.message = this.message;
		newReport.propertyMap.putAll(this.propertyMap);
		newReport.resultId = this.resultId;
		newReport.status = this.status;
		newReport.subject = this.subject;
		newReport.wasTimeConsuming = this.wasTimeConsuming;
		for (VisitReport childReport : this.subReports)
			newReport.subReports.add(childReport.clone());
		return newReport;
	}
}