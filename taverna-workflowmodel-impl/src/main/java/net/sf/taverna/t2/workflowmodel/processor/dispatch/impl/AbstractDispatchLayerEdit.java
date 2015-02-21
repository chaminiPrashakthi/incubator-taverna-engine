/*******************************************************************************
 * Copyright (C) 2007 The University of Manchester   
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
package net.sf.taverna.t2.workflowmodel.processor.dispatch.impl;

import net.sf.taverna.t2.workflowmodel.Edit;
import net.sf.taverna.t2.workflowmodel.EditException;
import net.sf.taverna.t2.workflowmodel.processor.dispatch.DispatchStack;

/**
 * Abstraction of an edit acting on a DispatchLayer instance. Handles the check to
 * see that the DispatchLayer supplied is really a DispatchLayerImpl.
 * 
 * @author Tom Oinn
 * 
 */
public abstract class AbstractDispatchLayerEdit implements Edit<DispatchStack> {
	private boolean applied = false;
	private DispatchStackImpl stack;

	protected AbstractDispatchLayerEdit(DispatchStack s) {
		if (s == null)
			throw new RuntimeException(
					"Cannot construct a dispatch stack edit with null dispatch stack");
		if (!(s instanceof DispatchStackImpl))
			throw new RuntimeException(
					"Edit cannot be applied to a DispatchStack which isn't "
					+ "an instance of DispatchStackImpl");
		stack = (DispatchStackImpl) s;
	}

	@Override
	public final DispatchStack doEdit() throws EditException {
		if (applied)
			throw new EditException("Edit has already been applied!");
		synchronized (stack) {
			doEditAction(stack);
			applied = true;
			return stack;
		}
	}

	/**
	 * Do the actual edit here
	 * 
	 * @param processor
	 *            The ProcessorImpl to which the edit applies
	 * @throws EditException
	 */
	protected abstract void doEditAction(DispatchStackImpl stack)
			throws EditException;

	/**
	 * Undo any edit effects here
	 */
	protected void undoEditAction(DispatchStackImpl stack) {
		throw new RuntimeException("undo not supported in the t2 model in T3");
	}

	@Override
	public final DispatchStack getSubject() {
		return stack;
	}

	@Override
	public final boolean isApplied() {
		return this.applied;
	}

	@Override
	public final void undo() {
		if (!applied)
			throw new RuntimeException(
					"Attempt to undo edit that was never applied");
		synchronized (stack) {
			undoEditAction(stack);
			applied = false;
		}
	}

}
