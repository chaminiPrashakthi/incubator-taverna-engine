/*******************************************************************************
 * Copyright (C) 2007-2008 The University of Manchester   
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
package net.sf.taverna.t2.workflowmodel.impl;

import net.sf.taverna.t2.workflowmodel.EditException;
import net.sf.taverna.t2.workflowmodel.processor.activity.AbstractActivity;
import net.sf.taverna.t2.workflowmodel.processor.activity.Activity;

/**
 * Abstraction of an edit acting on a Activity instance. Handles the check to
 * see that the Activity supplied is really a AbstractActivity.
 * 
 * @author David Withers
 * @author Stian Soiland-Reyes
 */
public abstract class AbstractActivityEdit<T> extends EditSupport<Activity<T>> {
	private final AbstractActivity<T> activity;

	protected AbstractActivityEdit(Activity<T> activity) {
		if (activity == null)
			throw new RuntimeException(
					"Cannot construct an activity edit with null activity");
		if (!(activity instanceof AbstractActivity))
			throw new RuntimeException(
					"Edit cannot be applied to an Activity which isn't an instance of AbstractActivity");
		this.activity = (AbstractActivity<T>) activity;
	}

	@Override
	public final Activity<T> applyEdit() throws EditException {
		synchronized (activity) {
			doEditAction(activity);
		}
		return activity;
	}

	/**
	 * Do the actual edit here
	 * 
	 * @param processor
	 *            The ProcessorImpl to which the edit applies
	 * @throws EditException
	 */
	protected abstract void doEditAction(AbstractActivity<T> processor)
			throws EditException;

	@Override
	public final Activity<T> getSubject() {
		return activity;
	}
}
