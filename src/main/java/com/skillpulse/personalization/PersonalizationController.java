package com.skillpulse.personalization;
import org.springframework.http.ResponseEntity; import org.springframework.validation.annotation.Validated; import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;
@Validated @RestController @RequestMapping("/api/personalization")
public class PersonalizationController {
    private final PersonalizationService service; private final PlanProgressService progress; private final com.skillpulse.auth.AuthService auth; public PersonalizationController(PersonalizationService service,PlanProgressService progress,com.skillpulse.auth.AuthService auth){this.service=service;this.progress=progress;this.auth=auth;}
    @GetMapping("/preferences") public ResponseEntity<PersonalizationDtos.PreferenceResponse> preferences(){return ResponseEntity.ok(service.getPreferences());}
    @PutMapping("/preferences") public ResponseEntity<PersonalizationDtos.PreferenceResponse> update(@Valid @RequestBody PersonalizationDtos.PreferenceRequest request){return ResponseEntity.ok(service.update(request));}
    @PostMapping("/plans/generate") public ResponseEntity<PersonalizationDtos.PlanResponse> generate(@RequestParam(value="subject",required=false) String subject){return ResponseEntity.ok(service.generate(subject));}
    @GetMapping("/plans/current") public ResponseEntity<java.util.Map<String,Object>> current(){return ResponseEntity.ok(progress.current(auth.requireUser(null).getId()));}
    @PostMapping("/plans/tasks/{taskId}/uncomplete") public ResponseEntity<java.util.Map<String,Object>> uncomplete(@PathVariable Long taskId){com.skillpulse.auth.AppUser user=auth.requireUser(null);progress.uncompleteTask(user,taskId);return ResponseEntity.ok(progress.current(user.getId()));}
    @PostMapping("/plans/reschedule") public ResponseEntity<java.util.Map<String,Object>> reschedule(@RequestParam(value="startTomorrow",defaultValue="false") boolean startTomorrow){return ResponseEntity.ok(progress.reschedule(auth.requireUser(null),startTomorrow));}
    @PostMapping("/plans/tasks/{taskId}/complete") public ResponseEntity<java.util.Map<String,Object>> complete(@PathVariable Long taskId){com.skillpulse.auth.AppUser user=auth.requireUser(null);progress.completeTask(user,taskId);return ResponseEntity.ok(progress.current(user.getId()));}
}
