/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * 
 */
package com.netflix.conductor.core.execution.tasks;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.Task.Status;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.Workflow.WorkflowStatus;
import com.netflix.conductor.core.execution.WorkflowExecutor;

/**
 * @author Viren
 *
 */
public class SubWorkflow extends WorkflowSystemTask {

	private static final Logger logger = LoggerFactory.getLogger(SubWorkflow.class);
	
	public SubWorkflow() {
		super("SUB_WORKFLOW");
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public void start(Workflow workflow, Task task, WorkflowExecutor provider) throws Exception {
		
		super.start(workflow, task, provider);
		Map<String, Object> input = task.getInputData();
		String name = input.get("subWorkflowName").toString();
		int version = (int) input.get("subWorkflowVersion");
		Map<String, Object> wfInput = (Map<String, Object>) input.get("workflowInput");
		if (wfInput == null || wfInput.isEmpty()) {
			wfInput = input;
		}
		String correlationId = workflow.getCorrelationId();
		
		try {
			
			String subWorkflowId = provider.startWorkflow(name, version, wfInput, correlationId, workflow.getWorkflowId(), task.getTaskId(), null);
			task.getInputData().put("subWorkflowId", subWorkflowId);
			task.getOutputData().put("subWorkflowId", subWorkflowId);
			task.setStatus(Status.IN_PROGRESS);
			
		} catch (Exception e) {
			// If we are not able to start the sub workflow now, let's
			// continue for now and let the sweep job take care of it.
			logger.error(e.getMessage(), e);
		}
	}
	
	@Override
	public boolean execute(Workflow workflow, Task task, WorkflowExecutor provider) throws Exception {
		String workflowId = (String) task.getInputData().get("subWorkflowId");
		if(workflowId == null){
			workflowId = (String) task.getOutputData().get("subWorkflowId");	//This is for backward compatibility, can be removed in future.
		}
		
		if (task.getStatus().equals(Status.SCHEDULED)) {
			long timeSince = System.currentTimeMillis() - task.getScheduledTime();
			if(timeSince > 600_000) {
				start(workflow, task, provider);
				return true;	
			}else {
				return false;
			}				
		}
		
		Workflow subWorkflow = provider.getWorkflow(workflowId, false);
		WorkflowStatus subWorkflowStatus = subWorkflow.getStatus();
		if(!subWorkflowStatus.isTerminal()){
			return false;
		}
		task.setOutputData(subWorkflow.getOutput());
		if(subWorkflowStatus.isSuccessful()){
			task.setStatus(Status.COMPLETED);
		}else{
			task.setStatus(Status.FAILED);
		}
		return true;
	}
	
	@Override
	public void cancel(Workflow workflow, Task task, WorkflowExecutor provider) throws Exception {
		String workflowId = (String) task.getInputData().get("subWorkflowId");
		if(workflowId == null){
			workflowId = (String) task.getOutputData().get("subWorkflowId");	//TODO: Remove in the next release.  Only for the backward compatibility
		}
		Workflow subWorkflow = provider.getWorkflow(workflowId, false);
		subWorkflow.setStatus(WorkflowStatus.TERMINATED);
		provider.terminateWorkflow(subWorkflow, "Parent workflow has been terminated with status " + workflow.getStatus(), null);
	}

}
