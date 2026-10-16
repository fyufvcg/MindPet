package controller;

import org.springframework.web.bind.annotation.*;
import service.agent.AgentScheduleStore;
import java.util.*;

@RestController
@RequestMapping("/api/desktop/schedules")
public class AgentScheduleController {
    private final AgentScheduleStore schedules;
    public AgentScheduleController(AgentScheduleStore schedules) { this.schedules = schedules; }
    @GetMapping public Map<String,Object> list() { return Map.of("tasks", schedules.list()); }
    @PutMapping public Map<String,Object> save(@RequestBody List<Map<String,Object>> tasks,
        @RequestParam(defaultValue="false") boolean migration) {
        schedules.replace(tasks, migration); return list();
    }
    @PatchMapping("/{id}") public Map<String,Object> upsert(@PathVariable String id, @RequestBody Map<String,Object> task) {
        schedules.upsert(id, task); return list();
    }
    @DeleteMapping("/{id}") public Map<String,Object> delete(@PathVariable String id) { schedules.delete(id); return list(); }
    @DeleteMapping("/{id}/logs") public Map<String,Object> clear(@PathVariable String id) {
        schedules.clearLogs(id); return list();
    }
}
