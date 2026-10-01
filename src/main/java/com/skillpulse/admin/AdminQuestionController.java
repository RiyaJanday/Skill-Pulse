package com.skillpulse.admin;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class AdminQuestionController {
    private final AdminQuestionService service;
    public AdminQuestionController(AdminQuestionService service){this.service=service;}
    @GetMapping("/api/admin/questions") public List<AdminQuestionDtos.QuestionRow> questions(@RequestParam("token")String token){return service.list(token);}
    @PostMapping("/api/admin/questions") public AdminQuestionDtos.QuestionRow create(@RequestParam("token")String token,@RequestBody AdminQuestionDtos.QuestionRequest request){return service.create(token,request);}
    @PutMapping("/api/admin/questions/{id}") public AdminQuestionDtos.QuestionRow update(@PathVariable("id")Long id,@RequestParam("token")String token,@RequestBody AdminQuestionDtos.QuestionRequest request){return service.update(token,id,request);}
    @PostMapping("/api/admin/questions/{id}/status") public Map<String,Object> status(@PathVariable("id")Long id,@RequestParam("token")String token,@RequestParam("active")boolean active){return service.setActive(token,id,active);}
}
