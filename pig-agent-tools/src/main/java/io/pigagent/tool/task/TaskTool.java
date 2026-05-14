package io.pigagent.tool.task;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.task.TaskManager;
import io.pigagent.task.TaskStatus;

public final class TaskTool {
    private final TaskManager taskManager;

    public TaskTool(TaskManager taskManager) { this.taskManager = taskManager; }

    @Tool(description = "Create a new task with a title and description")
    public String createTask(
            @ToolParam(name = "title", description = "Task title") String title,
            @ToolParam(name = "description", description = "Task description") String description) {
        var task = taskManager.createTask(title, description);
        return "Created task [%s]: %s".formatted(task.id(), task.title());
    }

    @Tool(description = "List all tasks or filter by status (TODO, IN_PROGRESS, COMPLETED, AWAITING_HUMAN_INPUT)")
    public String listTasks(@ToolParam(name = "status", description = "Filter by status, or 'all'") String status) {
        var tasks = "all".equals(status) ? taskManager.getAllTasks()
                : taskManager.getTasksByStatus(TaskStatus.valueOf(status));
        if (tasks.isEmpty()) return "No tasks found.";
        StringBuilder sb = new StringBuilder();
        for (var t : tasks) sb.append("- [%s] %s (%s)\n".formatted(t.id(), t.title(), t.status()));
        return sb.toString();
    }

    @Tool(description = "Update a task's status")
    public String updateTaskStatus(
            @ToolParam(name = "task_id", description = "Task ID") String taskId,
            @ToolParam(name = "status", description = "New status") String status) {
        var task = taskManager.updateStatus(taskId, TaskStatus.valueOf(status));
        return "Updated task [%s] to %s".formatted(task.id(), task.status());
    }
}
